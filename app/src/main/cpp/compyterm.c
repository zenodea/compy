// JNI bridge between Kotlin and libghostty-vt.
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <ghostty/vt.h>

typedef struct {
  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttySearch search;
  /* Rows asked for above the screen. */
  int above;
} Term;

enum {
  META_COLS, META_ROWS, META_BG, META_FG, META_CURSOR_COLOR, META_CURSOR_VISIBLE, META_CURSOR_X,
  META_CURSOR_Y, META_CURSOR_STYLE, META_SCROLL_TOTAL, META_SCROLL_OFFSET, META_SCROLL_LEN,
  META_MOUSE, META_ALT, META_BRACKETED, META_CURSOR_BLINK, META_EXTRAS_LEN, META_ABOVE, META_COUNT
};

enum { FLAG_BOLD = 1, FLAG_ITALIC = 2, FLAG_FAINT = 4, FLAG_INVERSE = 8, FLAG_INVISIBLE = 16,
       FLAG_STRIKE = 32, FLAG_UNDERLINE = 64, FLAG_OVERLINE = 128, FLAG_BLINK = 256,
       FLAG_SELECTED = 512, FLAG_WIDE = 1024, FLAG_SPACER = 2048, FLAG_LINK = 4096 };

/* The underline's kind sits above the flags: 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. */
#define UNDERLINE_SHIFT 13

static inline jint rgb(GhosttyColorRgb c) {
  return (jint)(0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | c.b);
}

/* Scrollback kept per terminal on the phone. A snapshot brings the server's own limit, which can
 * be far larger, and every pane Compy keeps warm holds its scrollback in memory. */
#define SCROLLBACK_MAX_BYTES ((size_t)8 * 1024 * 1024)

/* Frees a handle and everything it owns. Every ghostty free accepts NULL. */
static void destroy(Term* h) {
  if (!h) return;
  ghostty_search_free(h->search);
  ghostty_render_state_row_cells_free(h->cells);
  ghostty_render_state_row_iterator_free(h->rows);
  ghostty_render_state_free(h->rs);
  ghostty_terminal_free(h->term);
  free(h);
}

/* Takes ownership of the terminal. NULL, with the terminal freed, when memory runs out. */
static Term* wrap(GhosttyTerminal t) {
  Term* h = calloc(1, sizeof(Term));
  if (!h) {
    ghostty_terminal_free(t);
    return NULL;
  }
  h->term = t;
  const size_t max_scrollback = SCROLLBACK_MAX_BYTES;
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &max_scrollback);
  if (ghostty_render_state_new(NULL, &h->rs) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &h->rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &h->cells) != GHOSTTY_SUCCESS) {
    destroy(h);
    return NULL;
  }
  return h;
}

/* Copies bytes into a new Java array, which Kotlin decodes as UTF-8. NewStringUTF wants modified
 * UTF-8 instead, so text from a program, such as an emoji or a cut-off sequence, isn't safe in it. */
static jbyteArray to_bytes(JNIEnv* env, const uint8_t* data, size_t len) {
  jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
  if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte*)data);
  return out;
}

JNIEXPORT jlong JNICALL Java_dev_compy_term_GhosttyTerminal_nativeNew(
    JNIEnv* env, jclass cls, jint cols, jint rows) {
  GhosttyTerminal t = NULL;
  if (ghostty_terminal_new(NULL, &t, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS) return 0;
  return (jlong)(intptr_t)wrap(t);
}

JNIEXPORT jlong JNICALL Java_dev_compy_term_GhosttyTerminal_nativeDecodeSnapshot(
    JNIEnv* env, jclass cls, jbyteArray data, jint len) {
  if (len < 0 || len > (*env)->GetArrayLength(env, data)) return 0;
  jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
  if (!bytes) return 0;
  GhosttySnapshotDecoder d = NULL;
  GhosttyTerminal t = NULL;
  GhosttyResult r = ghostty_snapshot_decoder_new_buf(NULL, &d, (const uint8_t*)bytes, (size_t)len);
  if (r == GHOSTTY_SUCCESS) {
    const bool compress = true;
    ghostty_snapshot_decoder_set(d, GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY, &compress);
    r = ghostty_snapshot_decoder_decode(d, &t);
  }
  if (d) ghostty_snapshot_decoder_free(d);
  (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
  if (r != GHOSTTY_SUCCESS || !t) return 0;
  return (jlong)(intptr_t)wrap(t);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeFree(
    JNIEnv* env, jclass cls, jlong handle) {
  destroy((Term*)(intptr_t)handle);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeWrite(
    JNIEnv* env, jclass cls, jlong handle, jbyteArray data, jint off, jint len) {
  Term* h = (Term*)(intptr_t)handle;
  if (off < 0 || len <= 0 || len > (*env)->GetArrayLength(env, data) - off) return;
  jbyte* bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
  if (!bytes) return;
  ghostty_terminal_vt_write(h->term, (const uint8_t*)bytes + off, (size_t)len);
  (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeResize(
    JNIEnv* env, jclass cls, jlong handle, jint cols, jint rows, jint cw, jint ch) {
  Term* h = (Term*)(intptr_t)handle;
  ghostty_terminal_resize(h->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cw, (uint32_t)ch);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeScroll(
    JNIEnv* env, jclass cls, jlong handle, jint delta) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyTerminalScrollViewport b = {0};
  b.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
  b.value.delta = delta;
  ghostty_terminal_scroll_viewport(h->term, b);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeScrollToBottom(
    JNIEnv* env, jclass cls, jlong handle) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyTerminalScrollViewport b = {0};
  b.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
  ghostty_terminal_scroll_viewport(h->term, b);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSetColors(
    JNIEnv* env, jclass cls, jlong handle, jint fg, jint bg, jint cursor, jintArray palette) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyColorRgb f = {(uint8_t)(fg >> 16), (uint8_t)(fg >> 8), (uint8_t)fg};
  GhosttyColorRgb b = {(uint8_t)(bg >> 16), (uint8_t)(bg >> 8), (uint8_t)bg};
  GhosttyColorRgb c = {(uint8_t)(cursor >> 16), (uint8_t)(cursor >> 8), (uint8_t)cursor};
  ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, fg == 0 ? NULL : &f);
  ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, bg == 0 ? NULL : &b);
  ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, cursor == 0 ? NULL : &c);
  if (palette == NULL || (*env)->GetArrayLength(env, palette) < 256) {
    ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, NULL);
  } else {
    jint values[256];
    (*env)->GetIntArrayRegion(env, palette, 0, 256, values);
    GhosttyColorRgb pal[256];
    for (int i = 0; i < 256; i++) {
      pal[i].r = (uint8_t)(values[i] >> 16);
      pal[i].g = (uint8_t)(values[i] >> 8);
      pal[i].b = (uint8_t)values[i];
    }
    ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
  }
}

// Fills the frame arrays. Returns false when nothing changed since the last call.
// cells holds 4 ints per cell: codepoint (or -(index+1) into extras), fg, bg, flags.
// A colour of 0 means "use the terminal default".
// The screen's rows are followed by up to `above` rows of the lines just above the screen, nearest
// first, for a view scrolled part of a row into history or one taller than its terminal. META_ABOVE
// says how many there were.
JNIEXPORT jboolean JNICALL Java_dev_compy_term_GhosttyTerminal_nativeRender(
    JNIEnv* env, jclass cls, jlong handle, jintArray metaArr, jintArray cellsArr, jintArray extrasArr,
    jboolean force, jint above) {
  Term* h = (Term*)(intptr_t)handle;
  if (above < 0) above = 0;
  if (above > 512) above = 512;
  if (above != h->above) {
    h->above = above;
    const GhosttyRenderStateOverscan overscan = { .above = (uint16_t)above, .below = 0 };
    ghostty_render_state_set(h->rs, GHOSTTY_RENDER_STATE_OPTION_OVERSCAN, &overscan);
    force = JNI_TRUE;
  }
  if (ghostty_render_state_update(h->rs, h->term) != GHOSTTY_SUCCESS) return JNI_FALSE;
  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  if (!force && dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return JNI_FALSE;

  jint meta[META_COUNT];
  memset(meta, 0, sizeof(meta));
  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  meta[META_COLS] = cols;
  meta[META_ROWS] = rows;

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
  meta[META_BG] = rgb(colors.background);
  meta[META_FG] = rgb(colors.foreground);
  meta[META_CURSOR_COLOR] = colors.cursor_has_value ? rgb(colors.cursor) : 0;

  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);
  meta[META_CURSOR_VISIBLE] = cursor.visible && cursor.viewport_has_value;
  meta[META_CURSOR_X] = cursor.viewport_x;
  meta[META_CURSOR_Y] = cursor.viewport_y;
  meta[META_CURSOR_STYLE] = (jint)cursor.visual_style;
  meta[META_CURSOR_BLINK] = cursor.blinking;

  GhosttyTerminalScrollbar sb = {0};
  ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
  meta[META_SCROLL_TOTAL] = (jint)sb.total;
  meta[META_SCROLL_OFFSET] = (jint)sb.offset;
  meta[META_SCROLL_LEN] = (jint)sb.len;

  bool mouse = false;
  ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
  meta[META_MOUSE] = mouse;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  meta[META_ALT] = screen != GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  GhosttyTerminalModeConfig bracket = {0};
  bracket.mode = GHOSTTY_MODE_BRACKETED_PASTE;
  ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_MODE, &bracket);
  meta[META_BRACKETED] = bracket.value;

  jsize cap = (*env)->GetArrayLength(env, cellsArr);
  jsize extrasCap = (*env)->GetArrayLength(env, extrasArr);
  jsize needed = (jsize)cols * (rows + above) * 4;
  jint extrasLen = 0;
  if (needed <= cap) {
    jint* out = (*env)->GetPrimitiveArrayCritical(env, cellsArr, NULL);
    if (!out) return JNI_FALSE;
    jint* extras = (*env)->GetPrimitiveArrayCritical(env, extrasArr, NULL);
    if (!extras) {
      (*env)->ReleasePrimitiveArrayCritical(env, cellsArr, out, JNI_ABORT);
      return JNI_FALSE;
    }
    /* Cells the iterators don't reach would otherwise keep what the last frame drew there. */
    memset(out, 0, (size_t)needed * sizeof(jint));
    ghostty_render_state_get(h->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &h->rows);
    while (ghostty_render_state_row_iterator_next(h->rows)) {
      int32_t at = 0;
      if (ghostty_render_state_row_get(h->rows, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &at) != GHOSTTY_SUCCESS) continue;
      if (at < -above || at >= rows) continue;
      int y = at;
      if (at < 0) {
        y = rows - 1 - at;
        if (-at > meta[META_ABOVE]) meta[META_ABOVE] = -at;
      }
      GhosttyRenderStateRowSelection sel = GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
      bool hasSel = ghostty_render_state_row_get(h->rows, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS;
      ghostty_render_state_row_get(h->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &h->cells);
      int x = 0;
      jint* row = out + (size_t)y * cols * 4;
      while (x < cols && ghostty_render_state_row_cells_next(h->cells)) {
        jint* c = row + (size_t)x * 4;
        jint cp = 0, fg = 0, bg = 0, flags = 0;
        uint32_t glen = 0;
        ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
        if (glen > 0) {
          uint32_t buf[32];
          uint32_t tmp[256];
          uint32_t* gb = glen <= 32 ? buf : (glen <= 256 ? tmp : NULL);
          if (gb) {
            ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, gb);
            if (glen == 1) {
              cp = (jint)gb[0];
            } else if ((jsize)(extrasLen + glen + 1) <= extrasCap) {
              cp = -(extrasLen + 1);
              extras[extrasLen++] = (jint)glen;
              for (uint32_t i = 0; i < glen; i++) extras[extrasLen++] = (jint)gb[i];
            } else {
              cp = (jint)gb[0];
            }
          }
        }
        GhosttyColorRgb col;
        if (ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &col) == GHOSTTY_SUCCESS) bg = rgb(col);
        bool styled = false;
        ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &styled);
        if (styled) {
          if (ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &col) == GHOSTTY_SUCCESS) fg = rgb(col);
          GhosttyStyle style;
          memset(&style, 0, sizeof(style));
          ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
          if (style.flags & GHOSTTY_STYLE_FLAG_BOLD) flags |= FLAG_BOLD;
          if (style.flags & GHOSTTY_STYLE_FLAG_ITALIC) flags |= FLAG_ITALIC;
          if (style.flags & GHOSTTY_STYLE_FLAG_FAINT) flags |= FLAG_FAINT;
          if (style.flags & GHOSTTY_STYLE_FLAG_INVERSE) flags |= FLAG_INVERSE;
          if (style.flags & GHOSTTY_STYLE_FLAG_INVISIBLE) flags |= FLAG_INVISIBLE;
          if (style.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) flags |= FLAG_STRIKE;
          if (style.flags & GHOSTTY_STYLE_FLAG_OVERLINE) flags |= FLAG_OVERLINE;
          if (style.flags & GHOSTTY_STYLE_FLAG_BLINK) flags |= FLAG_BLINK;
          int underline = GHOSTTY_STYLE_UNDERLINE(style);
          if (underline != 0) flags |= FLAG_UNDERLINE | ((underline & 7) << UNDERLINE_SHIFT);
        }
        GhosttyCell raw = 0;
        if (ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
          GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
          ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
          if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= FLAG_WIDE;
          else if (wide != GHOSTTY_CELL_WIDE_NARROW) flags |= FLAG_SPACER;
          bool link = false;
          ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &link);
          if (link) flags |= FLAG_LINK;
        }
        if (hasSel && x >= sel.start_x && x <= sel.end_x) flags |= FLAG_SELECTED;
        c[0] = cp; c[1] = fg; c[2] = bg; c[3] = flags;
        x++;
      }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, extrasArr, extras, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, cellsArr, out, 0);
  }
  meta[META_EXTRAS_LEN] = extrasLen;
  (*env)->SetIntArrayRegion(env, metaArr, 0, META_COUNT, meta);
  ghostty_render_state_clean(h->rs);
  return JNI_TRUE;
}

static bool viewport_ref(Term* h, int x, int y, GhosttyGridRef* ref) {
  GhosttyPoint p = {0};
  p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  p.value.coordinate.x = (uint16_t)x;
  p.value.coordinate.y = (uint32_t)y;
  memset(ref, 0, sizeof(*ref));
  return ghostty_terminal_grid_ref(h->term, p, ref) == GHOSTTY_SUCCESS;
}

JNIEXPORT jboolean JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSelectRange(
    JNIEnv* env, jclass cls, jlong handle, jint x1, jint y1, jint x2, jint y2) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttySelection sel;
  memset(&sel, 0, sizeof(sel));
  if (!viewport_ref(h, x1, y1, &sel.start) || !viewport_ref(h, x2, y2, &sel.end)) return JNI_FALSE;
  return ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel) == GHOSTTY_SUCCESS;
}

JNIEXPORT jboolean JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSelectWord(
    JNIEnv* env, jclass cls, jlong handle, jint x, jint y) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyTerminalSelectWordOptions o = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
  if (!viewport_ref(h, x, y, &o.ref)) return JNI_FALSE;
  GhosttySelection sel;
  memset(&sel, 0, sizeof(sel));
  if (ghostty_terminal_select_word(h->term, &o, &sel) != GHOSTTY_SUCCESS) return JNI_FALSE;
  return ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel) == GHOSTTY_SUCCESS;
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeClearSelection(
    JNIEnv* env, jclass cls, jlong handle) {
  Term* h = (Term*)(intptr_t)handle;
  ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

// The selected text as UTF-8, or null when nothing is selected.
JNIEXPORT jbyteArray JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSelectionText(
    JNIEnv* env, jclass cls, jlong handle) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttySelection sel;
  memset(&sel, 0, sizeof(sel));
  if (ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) != GHOSTTY_SUCCESS) return NULL;
  GhosttyTerminalSelectionFormatOptions o = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  o.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  o.unwrap = true;
  o.trim = true;
  o.selection = &sel;
  uint8_t* out = NULL;
  size_t len = 0;
  if (ghostty_terminal_selection_format_alloc(h->term, NULL, &o, &out, &len) != GHOSTTY_SUCCESS || !out) return NULL;
  jbyteArray bytes = to_bytes(env, out, len);
  ghostty_free(NULL, out, len);
  return bytes;
}

// Returns a cell's OSC 8 hyperlink target as UTF-8, or null when it has none.
JNIEXPORT jbyteArray JNICALL Java_dev_compy_term_GhosttyTerminal_nativeHyperlinkAt(
    JNIEnv* env, jclass cls, jlong handle, jint x, jint y) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyGridRef ref;
  if (!viewport_ref(h, x, y, &ref)) return NULL;
  uint8_t buf[2048];
  size_t len = 0;
  if (ghostty_grid_ref_hyperlink_uri(&ref, buf, sizeof(buf), &len) != GHOSTTY_SUCCESS || len == 0) return NULL;
  return to_bytes(env, buf, len);
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeScrollPrompt(
    JNIEnv* env, jclass cls, jlong handle, jint delta) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyTerminalScrollViewport b = {0};
  b.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA_PROMPT;
  b.value.delta_prompt = delta;
  ghostty_terminal_scroll_viewport(h->term, b);
}

// Selects the command output at a cell. False if the shell hasn't marked output there.
JNIEXPORT jboolean JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSelectOutputAt(
    JNIEnv* env, jclass cls, jlong handle, jint x, jint y) {
  Term* h = (Term*)(intptr_t)handle;
  GhosttyGridRef ref;
  if (!viewport_ref(h, x, y, &ref)) return JNI_FALSE;
  GhosttySelection sel;
  memset(&sel, 0, sizeof(sel));
  if (ghostty_terminal_select_output(h->term, ref, &sel) != GHOSTTY_SUCCESS) return JNI_FALSE;
  return ghostty_terminal_set(h->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel) == GHOSTTY_SUCCESS;
}

// Starts or changes a search and returns how many matches it found.
JNIEXPORT jint JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSearch(
    JNIEnv* env, jclass cls, jlong handle, jbyteArray needle) {
  Term* h = (Term*)(intptr_t)handle;
  if (!h->search && ghostty_search_new(NULL, &h->search, h->term) != GHOSTTY_SUCCESS) return 0;
  jsize len = (*env)->GetArrayLength(env, needle);
  jbyte* bytes = (*env)->GetByteArrayElements(env, needle, NULL);
  if (!bytes) return 0;
  GhosttyString text = { (const uint8_t*)bytes, (size_t)len };
  GhosttyResult r = ghostty_search_set(h->search, GHOSTTY_SEARCH_OPT_NEEDLE, &text);
  if (r == GHOSTTY_SUCCESS) r = ghostty_search_run(h->search);
  (*env)->ReleaseByteArrayElements(env, needle, bytes, JNI_ABORT);
  if (r != GHOSTTY_SUCCESS) return 0;
  size_t total = 0;
  ghostty_search_get(h->search, GHOSTTY_SEARCH_DATA_TOTAL_MATCHES, &total);
  return (jint)total;
}

// Moves to the next or previous match, scrolling to it. Returns its index, or -1.
JNIEXPORT jint JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSearchStep(
    JNIEnv* env, jclass cls, jlong handle, jboolean older) {
  Term* h = (Term*)(intptr_t)handle;
  if (!h->search) return -1;
  GhosttyResult r = ghostty_search_set(h->search, older ? GHOSTTY_SEARCH_OPT_SELECT_NEXT : GHOSTTY_SEARCH_OPT_SELECT_PREV, NULL);
  if (r != GHOSTTY_SUCCESS) return -1;
  size_t index = 0;
  if (ghostty_search_get(h->search, GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &index) != GHOSTTY_SUCCESS) return -1;
  return (jint)index;
}

JNIEXPORT void JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSearchClear(
    JNIEnv* env, jclass cls, jlong handle) {
  Term* h = (Term*)(intptr_t)handle;
  ghostty_search_free(h->search);
  h->search = NULL;
}

static bool match_points(Term* h, const GhosttySelection* m, GhosttyPointCoordinate* a, GhosttyPointCoordinate* b) {
  return ghostty_terminal_point_from_grid_ref(h->term, &m->start, GHOSTTY_POINT_TAG_VIEWPORT, a) == GHOSTTY_SUCCESS &&
         ghostty_terminal_point_from_grid_ref(h->term, &m->end, GHOSTTY_POINT_TAG_VIEWPORT, b) == GHOSTTY_SUCCESS;
}

// Writes on-screen matches as start x, start y, end x, end y, selected. Returns how many it wrote.
JNIEXPORT jint JNICALL Java_dev_compy_term_GhosttyTerminal_nativeSearchHighlights(
    JNIEnv* env, jclass cls, jlong handle, jintArray out) {
  Term* h = (Term*)(intptr_t)handle;
  if (!h->search || ghostty_search_feed(h->search) != GHOSTTY_SUCCESS) return 0;
  GhosttySelection storage[128];
  GhosttySelectionBuffer buffer = { .ptr = storage, .cap = 128 };
  GhosttyResult r = ghostty_search_get(h->search, GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES, &buffer);
  if (r != GHOSTTY_SUCCESS && r != GHOSTTY_OUT_OF_SPACE) return 0;
  size_t found = buffer.len < 128 ? buffer.len : 128;

  GhosttyPointCoordinate selectedStart = {0}, selectedEnd = {0};
  GhosttySelection selected;
  memset(&selected, 0, sizeof(selected));
  bool hasSelected = ghostty_search_get(h->search, GHOSTTY_SEARCH_DATA_SELECTED_MATCH, &selected) == GHOSTTY_SUCCESS &&
                     match_points(h, &selected, &selectedStart, &selectedEnd);

  uint16_t rows = 0;
  ghostty_terminal_get(h->term, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  jsize cap = (*env)->GetArrayLength(env, out) / 5;
  jint* values = (*env)->GetIntArrayElements(env, out, NULL);
  if (!values) return 0;
  jint count = 0;
  for (size_t i = 0; i < found && count < cap; i++) {
    GhosttyPointCoordinate a, b;
    if (!match_points(h, &storage[i], &a, &b) || a.y >= rows || b.y >= rows) continue;
    jint* v = values + (size_t)count * 5;
    v[0] = a.x; v[1] = (jint)a.y; v[2] = b.x; v[3] = (jint)b.y;
    v[4] = hasSelected && a.x == selectedStart.x && a.y == selectedStart.y && b.x == selectedEnd.x && b.y == selectedEnd.y;
    count++;
  }
  (*env)->ReleaseIntArrayElements(env, out, values, 0);
  return count;
}
