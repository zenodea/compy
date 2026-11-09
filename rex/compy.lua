-- Compy for Rex: lets the Compy app show an agent's conversation as a chat.
--
-- Add this file next to your init.lua and load it from there:
--
--   dofile(os.getenv("HOME") .. "/.config/rex/compy.lua")
--
-- then reload (C-a r, or `rex config reload`). It adds three actions:
-- compy_transcript finds the transcript of the agent running in a pane and
-- returns part of it, with images left out and referenced by position;
-- compy_image returns one of those images, in parts; and compy_images lists
-- every image in the transcript. They read nothing else: the pane's agent
-- decides the file, and callers can't name one.

local terminal = "com.superlogical.terminal"
local VERSION = 2
local DEFAULT_BYTES = 256 * 1024
local MAX_BYTES = 1024 * 1024
-- A line still longer than this after its blobs are taken out is left out.
local MAX_LINE = 1024 * 1024
local READ_CHUNK = 256 * 1024
-- Base64 this long or longer is an image or document, sent apart from its line.
local MIN_BLOB = 200
local BLOB_PART = 512 * 1024
local MAX_LISTED_IMAGES = 300
local BLOB = '"data":"([%w+/=]+)"'

local function read_file(path)
  local f = io.open(path, "rb")
  if not f then return nil end
  local data = f:read("*a")
  f:close()
  return data
end

local function base(path)
  return path and (path:match("([^/]+)$")) or nil
end

-- Which agent a pane's foreground process is, from its name, argv[0] or script.
local function agent_of(fg)
  for _, name in ipairs{ fg.argv0, base(fg.invoked_path), fg.name, base(fg.script) } do
    if name == "claude" then return "claude" end
  end
  return nil
end

-- Each agent keeps its transcript somewhere of its own.
local locators = {}

-- Claude Code: <config>/sessions/<pid>.json names the session, and the
-- transcript is <config>/projects/<cwd, non-alphanumerics as "-">/<id>.jsonl.
locators.claude = function(fg, home)
  local dir = os.getenv("CLAUDE_CONFIG_DIR") or (home .. "/.claude")
  local text = read_file(dir .. "/sessions/" .. tostring(fg.pid) .. ".json")
  if not text then return nil end
  local ok, session = pcall(rex.json.decode, text)
  if not ok or type(session) ~= "table" or type(session.sessionId) ~= "string" then return nil end
  local cwd = session.cwd or fg.cwd or ""
  local project = (cwd:gsub("[^%w]", "-"))
  return dir .. "/projects/" .. project .. "/" .. session.sessionId .. ".jsonl", session.sessionId
end

-- A line with its long base64 blobs replaced by where they are: the line's
-- offset and the blob's place in it, which compy_image takes.
local function tidy(line, offset)
  if #line >= MIN_BLOB and line:find('"data":"', 1, true) then
    local index = 0
    line = line:gsub(BLOB, function(data)
      if #data < MIN_BLOB then return nil end
      index = index + 1
      return '"data":"","compy_blob":{"offset":' .. string.format("%d", offset) .. ',"index":' .. index .. ',"bytes":' .. #data .. '}'
    end)
  end
  -- Claude Code also keeps a copy of each image it read; the chat never needs it.
  if #line >= MIN_BLOB and line:find('"base64":"', 1, true) then
    line = line:gsub('"base64":"([%w+/=]+)"', function(data)
      if #data < MIN_BLOB then return nil end
      return '"base64":""'
    end)
  end
  if #line > MAX_LINE then
    return '{"type":"compy-omitted","bytes":' .. #line .. '}'
  end
  return line
end

-- Complete lines from start, until about `budget` bytes. A line still being
-- written (no newline yet) is left for next time.
local function forward(f, start, size, budget)
  local lines, used, pos = {}, 0, start
  local pending = ""
  local pending_at = start
  f:seek("set", start)
  while pos < size and used < budget do
    local chunk = f:read(math.min(READ_CHUNK, size - pos))
    if not chunk or #chunk == 0 then break end
    pos = pos + #chunk
    pending = pending .. chunk
    pending_at = pos - #pending
    local from = 1
    while true do
      local nl = pending:find("\n", from, true)
      if not nl then break end
      local line = tidy(pending:sub(from, nl - 1), pending_at + from - 1)
      lines[#lines + 1] = line
      used = used + #line
      from = nl + 1
      if used >= budget then break end
    end
    pending = pending:sub(from)
    if #pending > 32 * MAX_LINE then
      -- A line too long to keep: skip to its end.
      lines[#lines + 1] = '{"type":"compy-omitted","bytes":' .. #pending .. '}'
      pending = ""
      f:read("*l")
      pos = f:seek("cur")
    end
  end
  return lines, pos - #pending
end

-- Complete lines ending at `stop`, about `budget` bytes of them, newest kept.
local function backward(f, stop, budget)
  local window = budget
  while true do
    local start = math.max(0, stop - window)
    f:seek("set", start)
    local data = f:read(stop - start) or ""
    local from = 1
    if start > 0 then
      local nl = data:find("\n", 1, true)
      if not nl then from = nil else from = nl + 1 end
    end
    if from and (from <= #data or start == 0) then
      local lines, first = {}, start + from - 1
      local pos = from
      while pos <= #data do
        local nl = data:find("\n", pos, true)
        if not nl then break end
        lines[#lines + 1] = tidy(data:sub(pos, nl - 1), start + pos - 1)
        pos = nl + 1
      end
      return lines, first, start + pos - 1
    end
    if start == 0 or window >= 64 * MAX_LINE then return {}, stop, stop end
    window = window * 4
  end
end

-- The transcript of the agent in the pane: the open file, or a result saying why not.
local function open_transcript()
  local p = rex.block.call(terminal, "process", {})
  local fg = p and p.foreground
  if not fg then return nil, { version = VERSION, available = false, reason = "no_process" } end
  local agent = agent_of(fg)
  if not agent then return nil, { version = VERSION, available = false, reason = "no_agent" } end
  local path, session = locators[agent](fg, p.home or os.getenv("HOME") or "")
  local f = path and io.open(path, "rb")
  return f, { version = VERSION, available = true, agent = agent, session = session or base(path) or "" }
end

local function transcript(ctx, args)
  local f, info = open_transcript()
  if not info.available then return info end
  -- A new session has no transcript until its first message.
  if not f then
    info.size, info.start, info.stop, info.lines = 0, 0, 0, {}
    return info
  end
  local size = f:seek("end")
  local budget = math.min(MAX_BYTES, tonumber(args.max_bytes) or DEFAULT_BYTES)
  local lines, start, stop
  local after = tonumber(args.after)
  if after and after <= size then
    start = after
    lines, stop = forward(f, after, size, budget)
  else
    -- No offset (or the file shrank): the newest lines, or those before `before`.
    lines, start, stop = backward(f, math.min(size, tonumber(args.before) or size), budget)
  end
  f:close()
  info.size, info.start, info.stop, info.lines = size, start, stop, lines
  return info
end

-- The index-th long blob of the line at offset, counted as tidy counts them.
local function blob_at(f, offset, index)
  f:seek("set", offset)
  local line = f:read("*l")
  if not line then return nil end
  local n = 0
  for data in line:gmatch(BLOB) do
    if #data >= MIN_BLOB then
      n = n + 1
      if n == index then return data end
    end
  end
  return nil
end

-- One part of an image (or other blob) that compy_transcript left out, as base64.
local function image(ctx, args)
  local f, info = open_transcript()
  if not f then return { available = false } end
  local data = blob_at(f, tonumber(args.offset) or 0, tonumber(args.index) or 1)
  f:close()
  if not data then return { available = false } end
  local from = math.max(0, tonumber(args.from) or 0)
  local part = data:sub(from + 1, from + BLOB_PART)
  return { available = true, bytes = #data, from = from, data = part, done = from + #part >= #data }
end

-- Every image in the transcript, newest last: where each is and how big.
local function images(ctx, args)
  local f, info = open_transcript()
  if not f then
    info.images = {}
    return info
  end
  local found, pending, pending_at, pos = {}, "", 0, 0
  while true do
    local chunk = f:read(READ_CHUNK)
    if not chunk or #chunk == 0 then break end
    pending = pending .. chunk
    pos = pos + #chunk
    pending_at = pos - #pending
    local from = 1
    while true do
      local nl = pending:find("\n", from, true)
      if not nl then break end
      local line = pending:sub(from, nl - 1)
      if line:find('"type":"image"', 1, true) then
        local n, at = 0, 1
        while true do
          local s, e, data = line:find(BLOB, at)
          if not s then break end
          if #data >= MIN_BLOB then
            n = n + 1
            -- Only images, not documents: an image block opens just before its data.
            if line:sub(math.max(1, s - 160), s):find('"type":"image"', 1, true) then
              found[#found + 1] = { offset = pending_at + from - 1, index = n, bytes = #data }
            end
          end
          at = e + 1
        end
      end
      from = nl + 1
    end
    pending = pending:sub(from)
  end
  f:close()
  local first = math.max(1, #found - MAX_LISTED_IMAGES + 1)
  local list = {}
  for i = first, #found do list[#list + 1] = found[i] end
  info.images = list
  return info
end

rex.action{
  name = "compy_transcript",
  title = "Compy: Read Agent Transcript",
  args = { after = "number?", before = "number?", max_bytes = "number?" },
  run = transcript,
}

rex.action{
  name = "compy_image",
  title = "Compy: Read Agent Image",
  args = { offset = "number", index = "number", from = "number?" },
  run = image,
}

rex.action{
  name = "compy_images",
  title = "Compy: List Agent Images",
  args = {},
  run = images,
}
