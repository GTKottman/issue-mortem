package app.issuemortem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The picture (PC screenshot or blank page) with ink on top. Everything is kept in picture pixels, so the saved
 * picture is full size, and the strokes save to JSON so a note from History can be edited again.
 *
 * Tools: pen, highlighter, arrow, numbered marker (each with its own note), lasso (circle ink, then drag it), eraser.
 * Pen and highlighter: draw, then hold still for half a second and the stroke snaps to a line, square, rectangle,
 * triangle or circle. Two fingers pinch-zoom and pan; once the pen has touched, one finger pans too (palm
 * rejection). The grid is a writing guide only and is not saved. Clear, moves and marker deletes are steps in the
 * history, so Undo brings them back.
 */
public class DrawView extends View {
    public static final int PEN = 0, HIGHLIGHTER = 1, ARROW = 2, MARKER = 3, LASSO = 4, ERASER = 5;
    private static final int FREE = 0, LINE = 1, RECT = 2, OVAL = 3, TRI = 4; // stroke shapes
    private static final long HOLD_MS = 500;
    private static final int UNITS = 1280; // sizes are in 1/1280ths of the picture width

    /** Told when the history, the zoom or the selection changes, and when a marker needs its note. */
    public interface Listener {
        void changed();

        void markerNote(Marker m, boolean isNew);
    }

    /** One step of ink. Points are x, y, width triples in picture pixels. */
    private static final class Stroke {
        int tool, color, shape;
        boolean clearAll;
        float[] p = new float[96];
        int n;
        float[] s; // shape: LINE x0 y0 x1 y1, RECT/OVAL l t r b, TRI ax ay bx by cx cy
        float width;
        String text = ""; // a marker's note

        void add(float x, float y, float w) {
            if (n + 3 > p.length) p = Arrays.copyOf(p, p.length * 2);
            p[n++] = x;
            p[n++] = y;
            p[n++] = w;
        }

        void translate(float dx, float dy) {
            for (int i = 0; i < n; i += 3) {
                p[i] += dx;
                p[i + 1] += dy;
            }
            if (s != null) for (int i = 0; i < s.length; i += 2) {
                s[i] += dx;
                s[i + 1] += dy;
            }
        }
    }

    /** What the activity sees of a marker: its number and note. */
    public static final class Marker {
        public final int number;
        private final Stroke stroke;

        Marker(int number, Stroke stroke) {
            this.number = number;
            this.stroke = stroke;
        }

        public String getText() {
            return stroke.text;
        }

        public void setText(String t) {
            stroke.text = t;
        }
    }

    /** An undo step: a stroke added (Clear is one too), strokes moved, or a marker removed. */
    private static final class Op {
        Stroke added, removed;
        int index;
        List<Stroke> moved;
        float dx, dy;
    }

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint grid = new Paint();
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG); // lasso and selection outlines
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode clear = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Op> undo = new ArrayList<>(), redo = new ArrayList<>();
    private Listener listener;
    private Bitmap shot, inkLayer, selLayer;
    private Canvas inkCanvas;
    private Stroke current;
    private boolean stylusSeen, pressure = true, gridDots;
    private int tool = PEN, color = Color.rgb(255, 59, 78), gridDivs;
    private final float[] sizes = {3, 16, 4, 22, 0, 12};

    // lasso
    private final List<Float> lasso = new ArrayList<>();
    private final List<Stroke> selected = new ArrayList<>();
    private RectF selBox;
    private boolean dragging;
    private float dragX0, dragY0, dragDx, dragDy;

    // view = picture * scale + (ox, oy)
    private float scale = 1, ox, oy, fitScale = 1;

    // gestures
    private static final int NONE = 0, DRAW = 1, PAN = 2, PINCH = 3, NOTE = 4;
    private int mode = NONE, drawPointer = -1;

    // Each marker's note shows in a card beside it, in screen space (same size at any zoom). Tapping a card or a
    // marker's dot opens its note with any tool. The marker being edited hides its card (the editor sits there).
    private final List<RectF> cardRects = new ArrayList<>();
    private final List<Marker> cardMarkers = new ArrayList<>();
    private final TextPaint cardText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG), cardLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Stroke editing;
    private Marker tapped;

    // Crop: the part of the picture that Issue, Send and Save use (picture pixels; null = all of it). While cropping,
    // a finger or the pen drags the box's corners or edges, moves it from inside, or draws a new one from outside.
    private RectF crop, cropEdit;
    private boolean cropping;
    private int cropGrab; // which sides move: 1 left, 2 top, 4 right, 8 bottom; 15 = the whole box; 0 = a new box
    private float cropX, cropY;
    private final Paint cropShade = new Paint(), cropLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastX, lastY, span0, scale0, imgMidX, imgMidY;
    private float holdX, holdY;
    private final Runnable hold = this::snapToShape;

    public DrawView(Context c) {
        super(c);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(Color.argb(70, 140, 200, 255));
        guide.setStyle(Paint.Style.STROKE);
        guide.setColor(Color.rgb(139, 102, 255));
        number.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        number.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(Color.rgb(12, 11, 14));
    }

    // ---- setup and saving

    public void setShot(Bitmap b) {
        shot = b;
        inkLayer = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        inkCanvas = new Canvas(inkLayer);
        strokes.clear();
        undo.clear();
        redo.clear();
        current = null;
        crop = null;
        cropping = false;
        deselect();
        fit();
        notifyChanged();
    }

    public Bitmap getShot() {
        return shot;
    }

    /** The picture with the ink on it, at full size (no grid), cut to the crop if there is one. */
    public Bitmap render() {
        Bitmap out = shot.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(out).drawBitmap(inkLayer, 0, 0, null);
        if (crop == null) return out;
        android.graphics.Rect r = new android.graphics.Rect();
        crop.round(r);
        return Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height());
    }

    // ---- crop

    public boolean isCropping() {
        return cropping;
    }

    public boolean isCropped() {
        return crop != null;
    }

    /** Shows the crop box (the last crop, or the whole picture) to drag. */
    public void startCrop() {
        cropEdit = crop != null ? new RectF(crop) : new RectF(0, 0, shot.getWidth(), shot.getHeight());
        cropping = true;
        mode = NONE;
        cancelStroke();
        fit();
        invalidate();
    }

    /** keep: use the box. Otherwise the crop stays what it was. */
    public void endCrop(boolean keep) {
        if (keep) {
            boolean whole = cropEdit.left <= 0 && cropEdit.top <= 0
                    && cropEdit.right >= shot.getWidth() && cropEdit.bottom >= shot.getHeight();
            crop = whole ? null : new RectF(cropEdit);
        }
        cropping = false;
        cropEdit = null;
        invalidate();
        notifyChanged();
    }

    /** Back to the whole picture. */
    public void resetCrop() {
        if (cropping) cropEdit.set(0, 0, shot.getWidth(), shot.getHeight());
        else crop = null;
        invalidate();
        notifyChanged();
    }

    /** Darkens what the crop leaves out; while cropping, also the box and its corner handles. */
    private void drawCrop(Canvas c) {
        RectF b = cropping ? cropEdit : crop;
        if (b == null) return;
        float d = getResources().getDisplayMetrics().density;
        float l = ox + b.left * scale, t = oy + b.top * scale, r = ox + b.right * scale, bt = oy + b.bottom * scale;
        cropShade.setColor(cropping ? 0x99000000 : 0xB30D0424);
        Path out = new Path();
        out.setFillType(Path.FillType.EVEN_ODD);
        out.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        out.addRect(l, t, r, bt, Path.Direction.CW);
        c.drawPath(out, cropShade);
        if (!cropping) return;
        cropLine.setStyle(Paint.Style.STROKE);
        cropLine.setColor(Color.WHITE);
        cropLine.setStrokeWidth(1.5f * d);
        c.drawRect(l, t, r, bt, cropLine);
        cropLine.setStrokeWidth(4 * d);
        float k = 22 * d;
        for (float[] p : new float[][] {{l, t, 1, 1}, {r, t, -1, 1}, {l, bt, 1, -1}, {r, bt, -1, -1}}) {
            c.drawLine(p[0], p[1], p[0] + k * p[2], p[1], cropLine);
            c.drawLine(p[0], p[1], p[0], p[1] + k * p[3], cropLine);
        }
    }

    private void cropDown(float vx, float vy) {
        float reach = 28 * getResources().getDisplayMetrics().density / scale;
        float x = (vx - ox) / scale, y = (vy - oy) / scale;
        RectF b = cropEdit;
        cropGrab = 0;
        boolean inY = y > b.top - reach && y < b.bottom + reach, inX = x > b.left - reach && x < b.right + reach;
        if (inY && Math.abs(x - b.left) < reach) cropGrab |= 1;
        if (inX && Math.abs(y - b.top) < reach) cropGrab |= 2;
        if (inY && Math.abs(x - b.right) < reach) cropGrab |= 4;
        if (inX && Math.abs(y - b.bottom) < reach) cropGrab |= 8;
        if (cropGrab == 0 && b.contains(x, y)) cropGrab = 15;
        cropX = clampX(x);
        cropY = clampY(y);
        if (cropGrab == 0) b.set(cropX, cropY, cropX, cropY);
    }

    private void cropMove(float vx, float vy) {
        float x = clampX((vx - ox) / scale), y = clampY((vy - oy) / scale);
        RectF b = cropEdit;
        if (cropGrab == 15) {
            float dx = Math.max(-b.left, Math.min(shot.getWidth() - b.right, x - cropX));
            float dy = Math.max(-b.top, Math.min(shot.getHeight() - b.bottom, y - cropY));
            b.offset(dx, dy);
            cropX += dx;
            cropY += dy;
            return;
        }
        float min = 16 * getResources().getDisplayMetrics().density / scale;
        if (cropGrab == 0) {
            b.set(Math.min(cropX, x), Math.min(cropY, y), Math.max(cropX, x), Math.max(cropY, y));
            return;
        }
        if ((cropGrab & 1) != 0) b.left = Math.min(x, b.right - min);
        if ((cropGrab & 2) != 0) b.top = Math.min(y, b.bottom - min);
        if ((cropGrab & 4) != 0) b.right = Math.max(x, b.left + min);
        if ((cropGrab & 8) != 0) b.bottom = Math.max(y, b.top + min);
    }

    /** A box drawn by a tap (too small to mean anything) goes back to the whole picture. */
    private void cropUp() {
        float min = 16 * getResources().getDisplayMetrics().density / scale;
        if (cropEdit.width() < min || cropEdit.height() < min) cropEdit.set(0, 0, shot.getWidth(), shot.getHeight());
    }

    private float clampX(float x) {
        return Math.max(0, Math.min(shot.getWidth(), x));
    }

    private float clampY(float y) {
        return Math.max(0, Math.min(shot.getHeight(), y));
    }

    public String toJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Stroke s : strokes) {
            JSONObject o = new JSONObject();
            o.put("tool", s.tool).put("color", s.color).put("shape", s.shape).put("clear", s.clearAll)
                    .put("width", (double) s.width).put("text", s.text);
            JSONArray p = new JSONArray();
            for (int i = 0; i < s.n; i++) p.put((double) s.p[i]);
            o.put("p", p);
            if (s.s != null) {
                JSONArray sp = new JSONArray();
                for (float v : s.s) sp.put((double) v);
                o.put("s", sp);
            }
            a.put(o);
        }
        return a.toString();
    }

    /** Loads saved strokes (after setShot); each one becomes an undo step. */
    public void loadJson(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Stroke s = new Stroke();
            s.tool = o.getInt("tool");
            s.color = o.getInt("color");
            s.shape = o.getInt("shape");
            s.clearAll = o.getBoolean("clear");
            s.width = (float) o.getDouble("width");
            s.text = o.optString("text", "");
            JSONArray p = o.getJSONArray("p");
            for (int j = 0; j + 2 < p.length(); j += 3)
                s.add((float) p.getDouble(j), (float) p.getDouble(j + 1), (float) p.getDouble(j + 2));
            JSONArray sp = o.optJSONArray("s");
            if (sp != null) {
                s.s = new float[sp.length()];
                for (int j = 0; j < sp.length(); j++) s.s[j] = (float) sp.getDouble(j);
            }
            commit(s);
        }
        redrawInk();
    }

    /** Markers on the page now, in number order. */
    public List<Marker> markers() {
        List<Marker> out = new ArrayList<>();
        for (Stroke s : strokes) {
            if (s.clearAll) out.clear();
            else if (s.tool == MARKER) out.add(new Marker(out.size() + 1, s));
        }
        return out;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void notifyChanged() {
        if (listener != null) listener.changed();
    }

    // ---- tools

    public int getTool() {
        return tool;
    }

    public void setTool(int t) {
        tool = t;
        if (t != LASSO) deselect();
        invalidate();
    }

    public void setColor(int c) {
        color = c;
        if (tool == ERASER || tool == LASSO) setTool(PEN);
    }

    public static boolean hasSize(int t) {
        return t == PEN || t == HIGHLIGHTER || t == ARROW || t == ERASER;
    }

    /** Brush size of the current tool, 1-40. */
    public float getSize() {
        return sizes[tool];
    }

    public void setSize(float size) {
        sizes[tool] = size;
    }

    public boolean getPressure() {
        return pressure;
    }

    public void setPressure(boolean on) {
        pressure = on;
    }

    public void setGrid(int divs, boolean dots) {
        gridDivs = divs;
        gridDots = dots;
        invalidate();
    }

    // ---- history

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private void commit(Stroke s) {
        strokes.add(s);
        Op op = new Op();
        op.added = s;
        push(op);
    }

    private void push(Op op) {
        undo.add(op);
        redo.clear();
        notifyChanged();
    }

    private void apply(Op op, boolean forward) {
        if (op.added != null) {
            if (forward) strokes.add(op.added);
            else strokes.remove(strokes.size() - 1);
        } else if (op.removed != null) {
            if (forward) strokes.remove(op.index);
            else strokes.add(op.index, op.removed);
        } else {
            for (Stroke s : op.moved) s.translate(forward ? op.dx : -op.dx, forward ? op.dy : -op.dy);
        }
    }

    public void undo() {
        if (undo.isEmpty()) return;
        Op op = undo.remove(undo.size() - 1);
        apply(op, false);
        redo.add(op);
        deselect();
        redrawInk();
        notifyChanged();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Op op = redo.remove(redo.size() - 1);
        apply(op, true);
        undo.add(op);
        deselect();
        redrawInk();
        notifyChanged();
    }

    public void clear() {
        if (strokes.isEmpty() || strokes.get(strokes.size() - 1).clearAll) return;
        Stroke marker = new Stroke();
        marker.clearAll = true;
        commit(marker);
        deselect();
        redrawInk();
    }

    public void removeMarker(Marker m) {
        Op op = new Op();
        op.removed = m.stroke;
        op.index = strokes.indexOf(m.stroke);
        if (op.index < 0) return;
        strokes.remove(op.index);
        push(op);
        redrawInk();
    }

    // ---- zoom

    public void fit() {
        if (shot == null || getWidth() == 0) return;
        fitScale = Math.min(getWidth() / (float) shot.getWidth(), getHeight() / (float) shot.getHeight());
        scale = fitScale;
        ox = (getWidth() - shot.getWidth() * scale) / 2;
        oy = (getHeight() - shot.getHeight() * scale) / 2;
        invalidate();
        notifyChanged();
    }

    /** Where the picture sits on screen (zoom and position), to put back later with setView. */
    public float[] getView() {
        return new float[] {scale, ox, oy};
    }

    public void setView(float[] v) {
        scale = v[0];
        ox = v[1];
        oy = v[2];
        invalidate();
        notifyChanged();
    }

    /** Slides the picture up or down so this marker sits at screen height vy. */
    public void slideTo(Marker m, float vy) {
        oy = vy - m.stroke.p[1] * scale;
        invalidate();
        notifyChanged();
    }

    /** Zoom as a percentage of "fit to screen". */
    public int zoomPercent() {
        return Math.round(scale / fitScale * 100);
    }

    /** Zoom by a factor around the middle of the view (the - and + buttons). */
    public void zoomBy(float f) {
        zoomAround(getWidth() / 2f, getHeight() / 2f, scale * f);
    }

    private void zoomAround(float vx, float vy, float newScale) {
        newScale = Math.max(fitScale * 0.5f, Math.min(fitScale * 12f, newScale));
        float ix = (vx - ox) / scale, iy = (vy - oy) / scale;
        scale = newScale;
        ox = vx - ix * scale;
        oy = vy - iy * scale;
        invalidate();
        notifyChanged();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fit();
    }

    // ---- drawing

    private void redrawInk() {
        if (inkLayer == null) return;
        inkLayer.eraseColor(Color.TRANSPARENT);
        int markerNo = 0;
        for (Stroke s : strokes) {
            if (s.clearAll) {
                inkLayer.eraseColor(Color.TRANSPARENT);
                markerNo = 0;
            } else {
                if (s.tool == MARKER) markerNo++;
                drawStroke(inkCanvas, s, markerNo);
            }
        }
        invalidate();
    }

    private float unit() {
        return shot.getWidth() / (float) UNITS;
    }

    private void drawStroke(Canvas c, Stroke s, int markerNo) {
        ink.setXfermode(s.tool == ERASER ? clear : null);
        ink.setColor(s.tool == ERASER ? Color.BLACK : s.color);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        switch (s.tool) {
            case MARKER: {
                float r = s.width, x = s.p[0], y = s.p[1];
                ink.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, r, ink);
                number.setColor(isLight(s.color) ? Color.BLACK : Color.WHITE);
                number.setTextSize(r * 1.15f);
                c.drawText(String.valueOf(markerNo), x, y - (number.ascent() + number.descent()) / 2, number);
                break;
            }
            case ARROW:
                if (s.s != null) drawArrow(c, s);
                break;
            case HIGHLIGHTER:
                // One path, so the stroke doesn't darken where it crosses itself.
                ink.setAlpha(90);
                ink.setStrokeCap(Paint.Cap.SQUARE);
                ink.setStrokeWidth(s.width);
                c.drawPath(s.shape != FREE ? shapePath(s) : polyline(s), ink);
                break;
            default: // pen and eraser
                if (s.shape != FREE) {
                    ink.setStrokeWidth(s.width);
                    c.drawPath(shapePath(s), ink);
                } else if (s.n == 3) {
                    ink.setStyle(Paint.Style.FILL);
                    c.drawCircle(s.p[0], s.p[1], s.p[2] / 2, ink);
                } else {
                    for (int i = 3; i < s.n; i += 3) drawSegment(c, s, i);
                }
        }
        ink.setXfermode(null);
        ink.setAlpha(255);
    }

    private static boolean isLight(int c) {
        return Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 > 160;
    }

    private void drawSegment(Canvas c, Stroke s, int i) {
        ink.setStrokeWidth((s.p[i - 1] + s.p[i + 2]) / 2);
        c.drawLine(s.p[i - 3], s.p[i - 2], s.p[i], s.p[i + 1], ink);
    }

    private static Path polyline(Stroke s) {
        Path path = new Path();
        path.moveTo(s.p[0], s.p[1]);
        for (int i = 3; i < s.n; i += 3) path.lineTo(s.p[i], s.p[i + 1]);
        if (s.n == 3) path.lineTo(s.p[0] + 0.1f, s.p[1]);
        return path;
    }

    private static Path shapePath(Stroke s) {
        Path path = new Path();
        float[] v = s.s;
        switch (s.shape) {
            case LINE:
                path.moveTo(v[0], v[1]);
                path.lineTo(v[2], v[3]);
                break;
            case RECT:
                path.addRect(v[0], v[1], v[2], v[3], Path.Direction.CW);
                break;
            case OVAL:
                path.addOval(new RectF(v[0], v[1], v[2], v[3]), Path.Direction.CW);
                break;
            case TRI:
                path.moveTo(v[0], v[1]);
                path.lineTo(v[2], v[3]);
                path.lineTo(v[4], v[5]);
                path.close();
                break;
        }
        return path;
    }

    private void drawArrow(Canvas c, Stroke s) {
        float x0 = s.s[0], y0 = s.s[1], x1 = s.s[2], y1 = s.s[3];
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        if (len < 1) return;
        float ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float head = Math.min(len * 0.6f, s.width * 4.5f), half = head * 0.55f;
        float bx = x1 - ux * head, by = y1 - uy * head;
        ink.setStrokeWidth(s.width);
        c.drawLine(x0, y0, bx + ux, by + uy, ink);
        Path tip = new Path();
        tip.moveTo(x1, y1);
        tip.lineTo(bx - uy * half, by + ux * half);
        tip.lineTo(bx + uy * half, by - ux * half);
        tip.close();
        ink.setStyle(Paint.Style.FILL);
        c.drawPath(tip, ink);
        ink.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shot == null) return;
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        c.drawBitmap(shot, 0, 0, image);
        if (gridDivs > 0) {
            float step = shot.getWidth() / (float) gridDivs;
            if (gridDots) {
                grid.setStrokeWidth(4 / scale); // 4 screen pixels at any zoom
                grid.setStrokeCap(Paint.Cap.ROUND);
                for (float x = step; x < shot.getWidth(); x += step)
                    for (float y = step; y < shot.getHeight(); y += step) c.drawPoint(x, y, grid);
            } else {
                grid.setStrokeWidth(0); // hairline at any zoom
                for (float x = step; x < shot.getWidth(); x += step) c.drawLine(x, 0, x, shot.getHeight(), grid);
                for (float y = step; y < shot.getHeight(); y += step) c.drawLine(0, y, shot.getWidth(), y, grid);
            }
        }
        c.drawBitmap(inkLayer, 0, 0, image);
        if (dragging && selLayer != null) c.drawBitmap(selLayer, dragDx, dragDy, image);
        // The stroke being drawn shows live here and joins the ink layer when the pen lifts. Erasing goes straight to the ink layer.
        if (current != null && current.tool != ERASER) drawStroke(c, current, 0);

        guide.setStrokeWidth(2 / scale);
        guide.setPathEffect(new DashPathEffect(new float[] {10 / scale, 8 / scale}, 0));
        if (lasso.size() >= 4) {
            Path p = new Path();
            p.moveTo(lasso.get(0), lasso.get(1));
            for (int i = 2; i + 1 < lasso.size(); i += 2) p.lineTo(lasso.get(i), lasso.get(i + 1));
            c.drawPath(p, guide);
        }
        if (selBox != null) {
            RectF r = new RectF(selBox);
            r.offset(dragDx, dragDy);
            c.drawRect(r, guide);
        }
        c.restore();
        drawCrop(c);
        if (!cropping) drawCards(c);
    }

    private void drawCards(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        cardRects.clear();
        cardMarkers.clear();
        cardText.setColor(Color.WHITE);
        cardText.setTextSize(15 * d * getResources().getConfiguration().fontScale);
        cardFill.setColor(0xD9140735);
        cardLine.setStyle(Paint.Style.STROKE);
        cardLine.setStrokeWidth(d);
        cardLine.setColor(0x33FFFFFF);
        int w = (int) (260 * d), pad = (int) (12 * d);
        for (Marker m : markers()) {
            Stroke s = m.stroke;
            if (s == editing || s.text.trim().isEmpty()) continue;
            float sx = ox + s.p[0] * scale, sy = oy + s.p[1] * scale, r = s.width * scale;
            StaticLayout text = StaticLayout.Builder.obtain(s.text, 0, s.text.length(), cardText, w - 2 * pad)
                    .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            float h = text.getHeight() + 2 * pad;
            float left = sx + r + 8 * d;
            if (left + w > getWidth() - 8 * d) left = sx - r - 8 * d - w; // no room on the right: the left side
            float top = Math.max(8 * d, Math.min(getHeight() - h - 8 * d, sy - 22 * d));
            RectF box = new RectF(left, top, left + w, top + h);
            c.drawRoundRect(box, 12 * d, 12 * d, cardFill);
            c.drawRoundRect(box, 12 * d, 12 * d, cardLine);
            c.save();
            c.translate(left + pad, top + pad);
            text.draw(c);
            c.restore();
            cardRects.add(box);
            cardMarkers.add(m);
        }
    }

    /** The marker whose card or dot is under this screen point, or null. */
    private Marker noteAt(float vx, float vy) {
        for (int i = cardRects.size() - 1; i >= 0; i--) if (cardRects.get(i).contains(vx, vy)) return cardMarkers.get(i);
        float x = (vx - ox) / scale, y = (vy - oy) / scale;
        for (Marker m : markers()) {
            Stroke s = m.stroke;
            if (Math.hypot(s.p[0] - x, s.p[1] - y) <= s.width * 1.3f) return m;
        }
        return null;
    }

    /** Where a marker is on screen: x, y and its radius. */
    public float[] screenOf(Marker m) {
        Stroke s = m.stroke;
        return new float[] {ox + s.p[0] * scale, oy + s.p[1] * scale, s.width * scale};
    }

    /** The marker whose note is open (its card hides), or null. */
    public void setEditing(Marker m) {
        editing = m == null ? null : m.stroke;
        invalidate();
    }

    // ---- pen, highlighter, arrow and eraser strokes

    private void addPoint(float vx, float vy, float pr, boolean pen) {
        float x = (vx - ox) / scale, y = (vy - oy) / scale;
        float size = sizes[current.tool] * unit();
        boolean usesPressure = pen && pressure && (current.tool == PEN || current.tool == ERASER);
        float w = usesPressure ? size * (0.3f + 1.4f * Math.min(pr, 1f)) : size;
        if (current.tool == ARROW) {
            if (current.s == null) current.s = new float[] {x, y, x, y};
            current.s[2] = x;
            current.s[3] = y;
            current.width = size;
            return;
        }
        if (current.tool == HIGHLIGHTER) current.width = size;
        current.add(x, y, w);
        if (current.tool == ERASER && current.n >= 6) {
            ink.setXfermode(clear);
            ink.setStyle(Paint.Style.STROKE);
            drawSegment(inkCanvas, current, current.n - 3);
            ink.setXfermode(null);
        }
    }

    private void snapToShape() {
        Stroke s = current;
        if (s == null || (s.tool != PEN && s.tool != HIGHLIGHTER) || s.shape != FREE || s.n < 12) return;
        float[] b = bounds(s);
        if (Math.hypot(b[2] - b[0], b[3] - b[1]) * scale < 30) return; // too small to mean a shape
        recognize(s, b);
        if (s.tool == PEN) {
            float sum = 0;
            for (int i = 2; i < s.n; i += 3) sum += s.p[i];
            s.width = sum / (s.n / 3);
        }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        invalidate();
    }

    private static float[] bounds(Stroke s) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < s.n; i += 3) {
            x0 = Math.min(x0, s.p[i]);
            y0 = Math.min(y0, s.p[i + 1]);
            x1 = Math.max(x1, s.p[i]);
            y1 = Math.max(y1, s.p[i + 1]);
        }
        return new float[] {x0, y0, x1, y1};
    }

    /** The closest of line, rectangle (square), triangle and ellipse (circle). */
    private static void recognize(Stroke s, float[] b) {
        float x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3];
        float w = x1 - x0, h = y1 - y0, diag = (float) Math.hypot(w, h);
        int k = s.n / 3;
        float sx = s.p[0], sy = s.p[1], ex = s.p[s.n - 3], ey = s.p[s.n - 2];
        if (Math.hypot(ex - sx, ey - sy) > 0.3f * diag) { // open stroke: a line
            s.shape = LINE;
            s.s = new float[] {sx, sy, ex, ey};
            return;
        }
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        // triangle corners: farthest from the centre, farthest from that, farthest from the line between them
        float mx = 0, my = 0;
        for (int i = 0; i < s.n; i += 3) { mx += s.p[i]; my += s.p[i + 1]; }
        mx /= k; my /= k;
        int a = farthest(s, mx, my), bb = farthest(s, s.p[a], s.p[a + 1]);
        int c = 0;
        float best = -1;
        for (int i = 0; i < s.n; i += 3) {
            float d = segDist(s.p[i], s.p[i + 1], s.p[a], s.p[a + 1], s.p[bb], s.p[bb + 1]);
            if (d > best) { best = d; c = i; }
        }
        float eEllipse = 0, eRect = 0, eTri = 0;
        for (int i = 0; i < s.n; i += 3) {
            float x = s.p[i], y = s.p[i + 1];
            float nx = (x - cx) / Math.max(w / 2, 1), ny = (y - cy) / Math.max(h / 2, 1);
            eEllipse += Math.abs((float) Math.hypot(nx, ny) - 1) * (w + h) / 4;
            eRect += Math.min(Math.min(Math.abs(x - x0), Math.abs(x - x1)), Math.min(Math.abs(y - y0), Math.abs(y - y1)));
            eTri += Math.min(segDist(x, y, s.p[a], s.p[a + 1], s.p[bb], s.p[bb + 1]),
                    Math.min(segDist(x, y, s.p[bb], s.p[bb + 1], s.p[c], s.p[c + 1]),
                            segDist(x, y, s.p[c], s.p[c + 1], s.p[a], s.p[a + 1])));
        }
        if (eTri < eRect && eTri < eEllipse) {
            s.shape = TRI;
            s.s = new float[] {s.p[a], s.p[a + 1], s.p[bb], s.p[bb + 1], s.p[c], s.p[c + 1]};
            return;
        }
        // near-equal sides: a square or a circle
        if (w / Math.max(h, 1) > 0.8f && w / Math.max(h, 1) < 1.25f) {
            float r = (w + h) / 4;
            x0 = cx - r; x1 = cx + r; y0 = cy - r; y1 = cy + r;
        }
        s.shape = eRect <= eEllipse ? RECT : OVAL;
        s.s = new float[] {x0, y0, x1, y1};
    }

    private static int farthest(Stroke s, float x, float y) {
        int at = 0;
        double best = -1;
        for (int i = 0; i < s.n; i += 3) {
            double d = Math.hypot(s.p[i] - x, s.p[i + 1] - y);
            if (d > best) { best = d; at = i; }
        }
        return at;
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
        float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ---- markers

    private void tapMarker(float vx, float vy) {
        float x = (vx - ox) / scale, y = (vy - oy) / scale;
        for (Marker m : markers()) {
            Stroke s = m.stroke;
            if (Math.hypot(s.p[0] - x, s.p[1] - y) <= s.width * 1.3f) {
                if (listener != null) listener.markerNote(m, false);
                return;
            }
        }
        Stroke s = new Stroke();
        s.tool = MARKER;
        s.color = color;
        s.width = sizes[MARKER] * unit();
        s.add(x, y, 0);
        commit(s);
        redrawInk();
        List<Marker> all = markers();
        if (listener != null) listener.markerNote(all.get(all.size() - 1), true);
    }

    // ---- lasso

    private void deselect() {
        lasso.clear();
        selected.clear();
        selBox = null;
        dragging = false;
        dragDx = dragDy = 0;
    }

    private void finishLasso() {
        int n = lasso.size() / 2;
        selected.clear();
        selBox = null;
        if (n >= 3) {
            float[] poly = new float[n * 2];
            for (int i = 0; i < poly.length; i++) poly[i] = lasso.get(i);
            int from = 0;
            for (int i = 0; i < strokes.size(); i++) if (strokes.get(i).clearAll) from = i + 1;
            for (int i = from; i < strokes.size(); i++) {
                Stroke s = strokes.get(i);
                if (s.tool == ERASER) continue;
                if (inside(s, poly)) selected.add(s);
            }
            for (Stroke s : selected) {
                RectF r = strokeBounds(s);
                if (selBox == null) selBox = r;
                else selBox.union(r);
            }
        }
        lasso.clear();
        invalidate();
    }

    /** Most of a stroke inside the loop (a shape, arrow or marker: its centre). */
    private static boolean inside(Stroke s, float[] poly) {
        if (s.s != null || s.tool == MARKER) {
            RectF r = strokeBounds(s);
            return contains(poly, r.centerX(), r.centerY());
        }
        int in = 0, k = s.n / 3;
        for (int i = 0; i < s.n; i += 3) if (contains(poly, s.p[i], s.p[i + 1])) in++;
        return in * 2 > k;
    }

    private static RectF strokeBounds(Stroke s) {
        RectF r = new RectF();
        float pad;
        if (s.s != null) {
            r.set(s.s[0], s.s[1], s.s[0], s.s[1]);
            for (int i = 0; i < s.s.length; i += 2) r.union(s.s[i], s.s[i + 1]);
            pad = s.width;
        } else {
            r.set(s.p[0], s.p[1], s.p[0], s.p[1]);
            pad = s.tool == MARKER ? s.width : 0;
            for (int i = 0; i < s.n; i += 3) {
                r.union(s.p[i], s.p[i + 1]);
                pad = Math.max(pad, s.tool == HIGHLIGHTER ? s.width : s.p[i + 2]);
            }
        }
        r.inset(-pad, -pad);
        return r;
    }

    private static boolean contains(float[] poly, float x, float y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
            float xi = poly[i], yi = poly[i + 1], xj = poly[j], yj = poly[j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private void startDrag(float x, float y) {
        dragging = true;
        dragX0 = x;
        dragY0 = y;
        dragDx = dragDy = 0;
        // While dragging, the selection is its own picture; the rest stays on the ink layer.
        List<Stroke> all = new ArrayList<>(strokes);
        strokes.removeAll(selected);
        redrawInk();
        selLayer = Bitmap.createBitmap(shot.getWidth(), shot.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(selLayer);
        int markerNo = 0;
        for (Stroke s : all) {
            if (s.clearAll) markerNo = 0;
            else if (s.tool == MARKER) markerNo++;
            if (selected.contains(s)) drawStroke(sc, s, markerNo);
        }
        strokes.clear();
        strokes.addAll(all);
    }

    private void finishDrag() {
        if (Math.abs(dragDx) > 0.5f || Math.abs(dragDy) > 0.5f) {
            Op op = new Op();
            op.moved = new ArrayList<>(selected);
            op.dx = dragDx;
            op.dy = dragDy;
            apply(op, true);
            push(op);
            selBox.offset(dragDx, dragDy);
        }
        dragging = false;
        dragDx = dragDy = 0;
        selLayer = null;
        redrawInk();
    }

    // ---- touch

    private static boolean isPen(MotionEvent e, int i) {
        int t = e.getToolType(i);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER;
    }

    private void startStroke(MotionEvent e, int i) {
        boolean pen = isPen(e, i);
        if (pen) stylusSeen = true;
        mode = DRAW;
        drawPointer = e.getPointerId(i);
        float x = e.getX(i), y = e.getY(i);
        if (tool == MARKER) return; // placed when the pen lifts
        if (tool == LASSO) {
            float ix = (x - ox) / scale, iy = (y - oy) / scale;
            float pad = 24 / scale;
            if (selBox != null && ix > selBox.left - pad && ix < selBox.right + pad && iy > selBox.top - pad && iy < selBox.bottom + pad) {
                startDrag(ix, iy);
            } else {
                deselect();
                lasso.add(ix);
                lasso.add(iy);
            }
            return;
        }
        current = new Stroke();
        current.tool = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ? ERASER : tool;
        current.color = color;
        addPoint(x, y, e.getPressure(i), pen);
        holdX = x;
        holdY = y;
        removeCallbacks(hold);
        postDelayed(hold, HOLD_MS);
    }

    private void moveStroke(MotionEvent e, int pi) {
        boolean pen = isPen(e, pi);
        float x = e.getX(pi), y = e.getY(pi);
        if (tool == LASSO && current == null) {
            float ix = (x - ox) / scale, iy = (y - oy) / scale;
            if (dragging) {
                dragDx = ix - dragX0;
                dragDy = iy - dragY0;
            } else {
                lasso.add(ix);
                lasso.add(iy);
            }
            return;
        }
        if (current == null || current.shape != FREE) return; // a snapped shape stays put until the pen lifts
        for (int h = 0; h < e.getHistorySize(); h++) {
            addPoint(e.getHistoricalX(pi, h), e.getHistoricalY(pi, h), e.getHistoricalPressure(pi, h), pen);
        }
        addPoint(x, y, e.getPressure(pi), pen);
        if (Math.hypot(x - holdX, y - holdY) > 10) { // still moving: restart the hold timer
            holdX = x;
            holdY = y;
            removeCallbacks(hold);
            postDelayed(hold, HOLD_MS);
        }
    }

    private void cancelStroke() {
        removeCallbacks(hold);
        if (current != null && current.tool == ERASER) redrawInk(); // undo the live erase
        current = null;
        if (dragging) {
            dragging = false;
            dragDx = dragDy = 0;
            selLayer = null;
            redrawInk();
        }
        lasso.clear();
    }

    private void startPinch(MotionEvent e) {
        mode = PINCH;
        float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
        span0 = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        scale0 = scale;
        imgMidX = (mx - ox) / scale;
        imgMidY = (my - oy) / scale;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (shot == null) return false;
        int i = e.getActionIndex();
        if (cropping) {
            // One finger or the pen moves the crop box; a second finger cancels that and pinches or pans as usual.
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                requestUnbufferedDispatch(e);
                mode = DRAW;
                cropDown(e.getX(), e.getY());
            } else if (a == MotionEvent.ACTION_MOVE && mode == DRAW) {
                cropMove(e.getX(), e.getY());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (mode == DRAW) cropUp();
                mode = NONE;
            } else if (a == MotionEvent.ACTION_POINTER_DOWN && mode == DRAW) {
                cropUp();
                if (e.getPointerCount() >= 2) startPinch(e);
            } else if (mode != DRAW) {
                if (a == MotionEvent.ACTION_MOVE && mode == PINCH && e.getPointerCount() >= 2) {
                    float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                    float span = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    scale = Math.max(fitScale * 0.5f, Math.min(fitScale * 12f, scale0 * span / Math.max(span0, 1)));
                    ox = mx - imgMidX * scale;
                    oy = my - imgMidY * scale;
                    notifyChanged();
                } else if (a == MotionEvent.ACTION_POINTER_UP) mode = NONE;
            }
            invalidate();
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestUnbufferedDispatch(e);
                tapped = noteAt(e.getX(), e.getY());
                if (tapped != null) mode = NOTE;
                else if (isPen(e, 0) || !stylusSeen) startStroke(e, 0);
                else { mode = PAN; lastX = e.getX(); lastY = e.getY(); }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (isPen(e, i)) { // the pen came down while a palm or finger was already touching
                    cancelStroke();
                    startStroke(e, i);
                } else if (mode == PAN || (mode == DRAW && !isPenStroke(e))) {
                    cancelStroke();
                    if (e.getPointerCount() >= 2) startPinch(e);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == DRAW) {
                    int pi = e.findPointerIndex(drawPointer);
                    if (pi >= 0) moveStroke(e, pi);
                } else if (mode == PAN) {
                    ox += e.getX() - lastX;
                    oy += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                } else if (mode == PINCH && e.getPointerCount() >= 2) {
                    float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                    float span = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    scale = Math.max(fitScale * 0.5f, Math.min(fitScale * 12f, scale0 * span / Math.max(span0, 1)));
                    ox = mx - imgMidX * scale;
                    oy = my - imgMidY * scale;
                    notifyChanged();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == DRAW && e.getPointerId(i) == drawPointer) finish(e, i);
                else if (mode == PINCH) mode = NONE; // lifting one finger ends the pinch
                break;
            case MotionEvent.ACTION_UP:
                if (mode == DRAW) finish(e, 0);
                else if (mode == NOTE && listener != null) {
                    Marker up = noteAt(e.getX(), e.getY());
                    if (up != null && up.stroke == tapped.stroke) listener.markerNote(tapped, false);
                }
                mode = NONE;
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelStroke();
                mode = NONE;
                break;
        }
        invalidate();
        return true;
    }

    private boolean isPenStroke(MotionEvent e) {
        int pi = e.findPointerIndex(drawPointer);
        return pi >= 0 && isPen(e, pi);
    }

    private void finish(MotionEvent e, int i) {
        removeCallbacks(hold);
        mode = NONE;
        if (tool == MARKER && current == null) {
            tapMarker(e.getX(i), e.getY(i));
            return;
        }
        if (tool == LASSO && current == null) {
            if (dragging) finishDrag();
            else finishLasso();
            return;
        }
        if (current == null) return;
        Stroke s = current;
        current = null;
        if (s.tool == ARROW && (s.s == null || Math.hypot(s.s[2] - s.s[0], s.s[3] - s.s[1]) < 2)) return;
        commit(s);
        if (s.tool != ERASER || s.n == 3) drawStroke(inkCanvas, s, 0); // a long erase is already on the layer
    }
}
