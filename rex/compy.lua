-- Compy for Rex: lets the Compy app show an agent's conversation as a chat.
--
-- Add this file next to your init.lua and load it from there:
--
--   dofile(os.getenv("HOME") .. "/.config/rex/compy.lua")
--
-- then reload (C-a r, or `rex config reload`). It adds one action,
-- compy_transcript, that finds the transcript of the agent running in a pane
-- and returns part of it. It reads nothing else: the pane's agent decides the
-- file, and callers can't name one.

local terminal = "com.superlogical.terminal"
local VERSION = 1
local DEFAULT_BYTES = 256 * 1024
local MAX_BYTES = 1024 * 1024
-- A line longer than this has its base64 blobs (images) emptied; one still
-- longer after that is left out.
local BLOB_LINE = 64 * 1024
local MAX_LINE = 1024 * 1024
local READ_CHUNK = 256 * 1024

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

local function tidy(line)
  if #line > BLOB_LINE then
    line = line:gsub('"data":"[%w+/=]+"', '"data":""')
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
  f:seek("set", start)
  while pos < size and used < budget do
    local chunk = f:read(math.min(READ_CHUNK, size - pos))
    if not chunk or #chunk == 0 then break end
    pos = pos + #chunk
    pending = pending .. chunk
    local from = 1
    while true do
      local nl = pending:find("\n", from, true)
      if not nl then break end
      local line = tidy(pending:sub(from, nl - 1))
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
        lines[#lines + 1] = tidy(data:sub(pos, nl - 1))
        pos = nl + 1
      end
      return lines, first, start + pos - 1
    end
    if start == 0 or window >= 64 * MAX_LINE then return {}, stop, stop end
    window = window * 4
  end
end

local function transcript(ctx, args)
  local p = rex.block.call(terminal, "process", {})
  local fg = p and p.foreground
  if not fg then return { version = VERSION, available = false, reason = "no_process" } end
  local agent = agent_of(fg)
  if not agent then return { version = VERSION, available = false, reason = "no_agent" } end
  local path, session = locators[agent](fg, p.home or os.getenv("HOME") or "")
  local f = path and io.open(path, "rb")
  -- A new session has no transcript until its first message.
  if not f then return { version = VERSION, available = true, agent = agent, session = session or "", size = 0, start = 0, stop = 0, lines = {} } end
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
  return { version = VERSION, available = true, agent = agent, session = session or base(path), size = size, start = start, stop = stop, lines = lines }
end

rex.action{
  name = "compy_transcript",
  title = "Compy: Read Agent Transcript",
  args = { after = "number?", before = "number?", max_bytes = "number?" },
  run = transcript,
}
