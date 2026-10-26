package app.issuemortem;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opening the app captures the screen of the chosen computer (the Issue Mortem helper, GET /api/screen); Back goes to
 * the start page with Blank page, History and Settings. Draw on it, and write what's wrong into numbered markers, then:
 * Issue makes a GitHub issue from the picture and the markers; Send puts the picture and markers in the computer's
 * "AI Inbox" folder; Save puts them in "Saved Notes". All three also save to Download/Issue Mortem here and keep an
 * editable copy in History.
 */
public class MainActivity extends Activity {
    static final String INBOX = "/AI Inbox/";
    static final String SAVED = "/Saved Notes/";

    // Design tokens, from the "Crad" look: one deep indigo night family, white type in a condensed uppercase face for
    // titles and buttons, one fiery gradient (Wave.FIRE) for the main button and the wave, orange for active tools.
    static final int BG = 0xFF140735, CANVAS = 0xFF0D0424, RAISED = 0xFF210F4B, LINE = 0xFF2B1A5E, OUTLINE = 0xFF56408F;
    static final int TEXT = 0xFFFFFFFF, MUTED = 0xFFB7ABDA, ACCENT = 0xFFFC840D, ACTIVE_BG = 0xFF2E1766;
    static final int ONLINE = 0xFF4CD787;
    static final int[] COLORS = {
            Color.rgb(255, 59, 78), Color.rgb(255, 214, 10), Color.rgb(48, 209, 88),
            Color.rgb(10, 132, 255), Color.WHITE, Color.BLACK};
    static final String[] GRID_NAMES = {"Off", "Small", "Medium", "Large"};
    static final int[] GRID_DIVS = {0, 96, 64, 32};
    static final String[] TOOL_NAMES = {"Pen", "Highlighter", "Arrow", "Marker", "Lasso", "Eraser"};
    static final int[] TOOL_ICONS = {R.drawable.ic_pen, R.drawable.ic_highlighter, R.drawable.ic_arrow,
            R.drawable.ic_marker, R.drawable.ic_lasso, R.drawable.ic_eraser};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private History history;
    private Prefs prefs;
    private TextView status;
    private DrawView draw;
    /** A note written before notes moved into the markers (History); kept and still sent, but no longer edited. */
    private String legacyNote = "";
    private TextView send, save, issue;
    private boolean drawing, busy, onHistory, onSettings;

    // toolbar state
    // drawing page (layout D): tool orb + arc on the left, undo/redo/more top left, the dock at the bottom
    private Tool undo, redo;
    private ImageView orbIcon;
    private GradientDrawable orbRing;
    private FrameLayout arc;
    private final List<Tool> arcTools = new ArrayList<>();
    private final List<View> arcColors = new ArrayList<>();
    private Boolean arcOpen; // starts open on a tablet, folded on a phone, where it would cover the picture
    private boolean dockOpen = true;
    private View dock, dockTab, cropBar;
    private int color = COLORS[0], grid = 0;
    private boolean gridDots;

    // S Pen near the screen (hovering or touching): fingers do nothing, so a palm can't press anything.
    private boolean penNear, swallowing;
    private final Runnable penGone = () -> penNear = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        history = new History(this);
        prefs = new Prefs(this);
        showStart(null);
        // Android 13+ sends Back here instead of onBackPressed.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
    }

    /** Opening the app (or coming back to the start page) captures the computer's screen straight away. */
    @Override
    protected void onResume() {
        super.onResume();
        if (!drawing && !onHistory && !onSettings && !busy && prefs.computer() != null) capture();
    }

    // ---- pen nearby: ignore fingers

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isPen(e)) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) {
                penNear = true;
                getWindow().getDecorView().removeCallbacks(penGone);
            } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                penSoonGone();
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (isPen(e)) {
            penNear = true;
            getWindow().getDecorView().removeCallbacks(penGone);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) penSoonGone();
            return super.dispatchTouchEvent(e);
        }
        if (a == MotionEvent.ACTION_DOWN) swallowing = penNear;
        if (swallowing) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallowing = false;
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    private void penSoonGone() {
        View d = getWindow().getDecorView();
        d.removeCallbacks(penGone);
        d.postDelayed(penGone, 600); // the pen lifting out of range for a moment isn't a hand coming in
    }

    private static boolean isPen(MotionEvent e) {
        for (int i = 0; i < e.getPointerCount(); i++) {
            int t = e.getToolType(i);
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return true;
        }
        return false;
    }

    // ---- start page

    private void showStart(String message) {
        drawing = false;
        onHistory = false;
        onSettings = false;
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        markParams.bottomMargin = dp(24);
        page.addView(logo(96), markParams);
        TextView word = title("Issue Mortem", 40, 0.3f);
        LinearLayout.LayoutParams wp = wrap();
        wp.bottomMargin = dp(32);
        page.addView(word, wp);
        TextView capture = pill("Capture screen", true, v -> capture());
        capture.setTextSize(24);
        capture.setPadding(dp(48), dp(20), dp(48), dp(20));
        page.addView(capture, wrap());

        // More than one computer: pick which one the screenshot comes from.
        List<Prefs.Computer> computers = prefs.computers();
        if (computers.size() > 1) {
            LinearLayout from = new LinearLayout(this);
            from.setGravity(Gravity.CENTER_VERTICAL);
            from.addView(text("From", MUTED, 16));
            for (int i = 0; i < computers.size(); i++) {
                int pick = i;
                Row r = new Row(0, computers.get(i).name);
                r.setActive(i == prefs.current());
                r.setOnClickListener(v -> { prefs.setCurrent(pick); showStart(null); });
                from.addView(r, spaced(8));
            }
            LinearLayout.LayoutParams fp = wrap();
            fp.topMargin = dp(16);
            page.addView(from, fp);
        }

        LinearLayout more = new LinearLayout(this);
        more.setGravity(Gravity.CENTER);
        more.addView(pill("Blank page", false, v -> blankPage()));
        more.addView(pill("History", false, v -> showHistory()), spaced(12));
        more.addView(pill("Settings", false, v -> showSettings(null)), spaced(12));
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(16);
        page.addView(more, lp);
        status = text(message == null ? "" : message, MUTED, 16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(24), dp(24), 0);
        page.addView(status);
        LinearLayout outer = column();
        outer.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        outer.addView(new Wave(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        setPage(outer);
    }

    private void blankPage() {
        Bitmap b = Bitmap.createBitmap(2560, 1600, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        showDraw(b, null, "");
    }

    // ---- history page

    private void showHistory() {
        drawing = false;
        onHistory = true;
        LinearLayout page = column();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(BG);
        bar.addView(new Tool(R.drawable.ic_back, null, v -> showStart(null)));
        TextView title = title("History", 26, 0.12f);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title);
        page.addView(bar);
        page.addView(rule());

        List<History.Entry> entries = history.list();
        if (entries.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.addView(text("No notes yet", TEXT, 20), wrap());
            TextView hint = text("Save or send a note and it shows up here, ready to open and edit.", MUTED, 15);
            hint.setPadding(0, dp(8), 0, dp(24));
            empty.addView(hint, wrap());
            empty.addView(pill("Capture screen", true, v -> capture()), wrap());
            page.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            setPage(page);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(16), dp(16), dp(16), dp(16));
        int perRow = 3;
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(24);
                list.addView(row, rp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % perRow != 0) cp.leftMargin = dp(16);
            row.addView(card(entries.get(i)), cp);
        }
        // keep the last row's cards the same width as the others
        for (int k = entries.size() % perRow; k != 0 && k < perRow; k++) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, 1, 1);
            cp.leftMargin = dp(16);
            row.addView(new View(this), cp);
        }
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setPage(page);
    }

    private View card(History.Entry e) {
        LinearLayout card = column();
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, 10)));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackground(rounded(CANVAS, 10));
        img.setImageBitmap(e.thumb());
        card.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        TextView when = text(e.name.replace('.', ':').replaceFirst(":(\\d\\d)$", ""), TEXT, 15);
        when.setPadding(dp(4), dp(10), dp(4), 0);
        card.addView(when);
        String first = e.note().trim();
        if (first.isEmpty()) {
            try {
                first = firstMarkerNote(e.ink());
            } catch (Exception ignored) {
            }
        }
        int nl = first.indexOf('\n');
        if (nl >= 0) first = first.substring(0, nl);
        TextView snippet = text(first.isEmpty() ? "No note" : first, MUTED, 14);
        snippet.setSingleLine(true);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setPadding(dp(4), dp(2), dp(4), dp(4));
        card.addView(snippet);
        card.setOnClickListener(v -> {
            try {
                showDraw(e.shot(), e.ink(), e.note());
            } catch (Exception x) {
                Toast.makeText(this, "Couldn't open it: " + x.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete from History?")
                    .setMessage("The copies on the computer, on GitHub and in Download/Issue Mortem stay.")
                    .setPositiveButton("Delete", (d, w) -> { history.delete(e); showHistory(); })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        return card;
    }

    /** The first marker note in saved ink, for History's cards. */
    private static String firstMarkerNote(String ink) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(ink);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                if (o.optInt("tool") == DrawView.MARKER && !o.optString("text").trim().isEmpty()) return o.optString("text").trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---- drawing page

    private void showDraw(Bitmap shot, String ink, String noteText) {
        drawing = true;
        onHistory = false;
        LinearLayout page = column();

        // One DrawView for the whole session, so Retake keeps the tool, size, colour and grid.
        if (draw == null) {
            draw = new DrawView(this);
            draw.setColor(color);
            draw.setListener(new DrawView.Listener() {
                public void changed() {
                    refreshBar();
                }

                public void markerNote(DrawView.Marker m, boolean isNew) {
                    markerDialog(m, isNew);
                }
            });
        } else if (draw.getParent() != null) {
            ((ViewGroup) draw.getParent()).removeView(draw);
        }

        draw.setShot(shot);
        if (ink != null) {
            try {
                draw.loadJson(ink);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't load the ink: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        legacyNote = noteText == null ? "" : noteText;

        // The picture fills the page; everything else floats over it and folds away.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(draw, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        boolean phone = getResources().getConfiguration().screenWidthDp < 600;
        if (arcOpen == null) arcOpen = !phone;

        // Tools: an arc of tools and colours around the orb. The orb shows the tool and colour; tapping it folds the arc.
        arc = new FrameLayout(this);
        arcTools.clear();
        arcColors.clear();
        int toolR = phone ? 96 : 118, colorR = phone ? 160 : 196, cy = colorR + 24;
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            int pick = i;
            Tool t = round(TOOL_ICONS[i], v -> {
                if (draw.getTool() == pick) toolMenu(v); // the current tool again: its size and pen pressure
                else draw.setTool(pick);
                refreshBar();
            });
            t.setContentDescription(TOOL_NAMES[i]);
            double a = Math.toRadians(-75 + i * 30);
            arc.addView(t, at(36 + toolR * Math.cos(a), cy + toolR * Math.sin(a), 54));
            arcTools.add(t);
        }
        for (int i = 0; i < COLORS.length; i++) {
            int c = COLORS[i];
            View sw = new View(this);
            sw.setContentDescription("Colour " + (i + 1));
            sw.setOnClickListener(v -> {
                color = c;
                draw.setColor(c); // switches back to the pen from the eraser or lasso
                refreshBar();
            });
            double a = Math.toRadians(-62 + i * 24.8);
            arc.addView(sw, at(36 + colorR * Math.cos(a), cy + colorR * Math.sin(a), 32));
            arcColors.add(sw);
        }
        FrameLayout.LayoutParams arcParams = new FrameLayout.LayoutParams(dp(colorR + 72), dp(2 * cy), Gravity.START | Gravity.CENTER_VERTICAL);
        arcParams.leftMargin = dp(20);
        frame.addView(arc, arcParams);
        arc.setVisibility(arcOpen ? View.VISIBLE : View.GONE);

        FrameLayout orb = new FrameLayout(this);
        orb.setBackground(glass(0));
        orb.setContentDescription("Tools");
        View ring = new View(this);
        orbRing = new GradientDrawable();
        orbRing.setShape(GradientDrawable.OVAL);
        ring.setBackground(orbRing);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rp.setMargins(dp(6), dp(6), dp(6), dp(6));
        orb.addView(ring, rp);
        orbIcon = new ImageView(this);
        orbIcon.setColorFilter(TEXT);
        orb.addView(orbIcon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        orb.setOnClickListener(v -> {
            arcOpen = !arcOpen;
            arc.setVisibility(arcOpen ? View.VISIBLE : View.GONE);
        });
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.START | Gravity.CENTER_VERTICAL);
        op.leftMargin = dp(20);
        frame.addView(orb, op);

        // Undo, redo and More (Retake, Crop, Grid, Fit, Clear), top left.
        LinearLayout corner = new LinearLayout(this);
        undo = round(R.drawable.ic_undo, v -> draw.undo());
        redo = round(R.drawable.ic_redo, v -> draw.redo());
        undo.setContentDescription("Undo");
        redo.setContentDescription("Redo");
        Tool more = round(R.drawable.ic_more, v -> moreMenu(v));
        more.setContentDescription("More");
        corner.addView(undo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        corner.addView(redo, spacedBox(10));
        corner.addView(more, spacedBox(10));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(16), dp(16), 0, 0);
        frame.addView(corner, cp);

        // What's happening (capturing, sending, errors), top centre, only while there's something to say.
        status = text("", TEXT, 15);
        status.setBackground(glass(12));
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        status.setMaxWidth(dp(560));
        status.setVisibility(View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int n) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                status.setVisibility(e.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(16);
        frame.addView(status, sp);

        // The dock: Save, Send, Issue. Its chevron folds it down to a small tab.
        LinearLayout dock = new LinearLayout(this);
        this.dock = dock;
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(glass(20));
        dock.setPadding(dp(8), dp(8), dp(4), dp(8));
        save = pill(R.drawable.ic_save, "Save", false, v -> finish(SAVED, "Saved"));
        send = pill(R.drawable.ic_send, "Send", false, v -> finish(INBOX, "Sent"));
        issue = pill("Issue", true, v -> issueDialog());
        dock.addView(save);
        dock.addView(send, spaced(phone ? 6 : 10));
        dock.addView(issue, spaced(phone ? 6 : 10));
        ImageView fold = new ImageView(this);
        fold.setImageResource(R.drawable.ic_chevron);
        fold.setColorFilter(MUTED);
        fold.setPadding(dp(10), dp(10), dp(10), dp(10));
        fold.setContentDescription("Hide the buttons");
        dock.addView(fold, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockParams.bottomMargin = dp(20);
        frame.addView(dock, dockParams);
        ImageView tab = new ImageView(this);
        dockTab = tab;
        tab.setImageResource(R.drawable.ic_chevron);
        tab.setRotation(180);
        tab.setColorFilter(TEXT);
        tab.setPadding(dp(30), dp(4), dp(30), dp(4));
        GradientDrawable tabBg = glass(14);
        tabBg.setCornerRadii(new float[] {dp(14), dp(14), dp(14), dp(14), 0, 0, 0, 0});
        tab.setBackground(tabBg);
        tab.setContentDescription("Show the buttons");
        frame.addView(tab, new FrameLayout.LayoutParams(dp(88), dp(32), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        Runnable showDock = () -> {
            dock.setVisibility(dockOpen ? View.VISIBLE : View.GONE);
            tab.setVisibility(dockOpen ? View.GONE : View.VISIBLE);
        };
        fold.setOnClickListener(v -> { dockOpen = false; showDock.run(); });
        tab.setOnClickListener(v -> { dockOpen = true; showDock.run(); });
        showDock.run();

        // Cropping: Cancel, Whole picture and Done take the dock's place.
        LinearLayout crops = new LinearLayout(this);
        crops.setGravity(Gravity.CENTER_VERTICAL);
        crops.setBackground(glass(20));
        crops.setPadding(dp(8), dp(8), dp(8), dp(8));
        crops.addView(pill("Cancel", false, v -> endCrop(false)));
        crops.addView(pill("Whole", false, v -> draw.resetCrop()), spaced(phone ? 6 : 10));
        crops.addView(pill("Done", true, v -> endCrop(true)), spaced(phone ? 6 : 10));
        FrameLayout.LayoutParams cropParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cropParams.bottomMargin = dp(20);
        frame.addView(crops, cropParams);
        cropBar = crops;
        crops.setVisibility(draw.isCropping() ? View.VISIBLE : View.GONE);
        if (draw.isCropping()) {
            dock.setVisibility(View.GONE);
            tab.setVisibility(View.GONE);
        }

        drawPage = page;
        setPage(page);
        refreshBar();
    }

    @Override
    public void onBackPressed() {
        back();
    }

    /** Drawing, History or Settings: back to the start page. Start page: leave the app. */
    private void back() {
        if (drawing && !onSettings && draw.isCropping()) endCrop(false);
        else if (onSettings && settingsFrom != null) {
            onSettings = false;
            drawing = true;
            setPage(settingsFrom);
            settingsFrom = null;
            refreshBar();
        } else if (drawing || onHistory || onSettings) showStart(null);
        else finish();
    }

    /** Undo/redo availability, and the current tool and colour on the orb and in the arc. */
    private void refreshBar() {
        if (undo == null || !drawing) return;
        undo.setEnabled(draw.canUndo());
        redo.setEnabled(draw.canRedo());
        int t = draw.getTool();
        orbIcon.setImageResource(TOOL_ICONS[t]);
        orbRing.setStroke(dp(3), color);
        for (int i = 0; i < arcTools.size(); i++) arcTools.get(i).setActive(i == t);
        for (int i = 0; i < arcColors.size(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(COLORS[i]);
            d.setStroke(dp(COLORS[i] == color ? 3 : 2), COLORS[i] == color ? ACCENT : 0x99140735);
            arcColors.get(i).setBackground(d);
        }
    }

    /** A round, see-through button for the drawing page. */
    private Tool round(int iconRes, View.OnClickListener click) {
        Tool t = new Tool(iconRes, null, click);
        t.setPadding(0, 0, 0, 0);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass(0), null));
        return t;
    }

    /** See-through panel colour for things floating over the picture; radius 0 means a circle. */
    private GradientDrawable glass(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        if (radiusDp == 0) d.setShape(GradientDrawable.OVAL);
        else d.setCornerRadius(dp(radiusDp));
        d.setColor(0x99140735);
        d.setStroke(dp(1), 0x33FFFFFF);
        return d;
    }

    /** Places a square child of sizeDp centred on (x, y) dp inside a FrameLayout. */
    private FrameLayout.LayoutParams at(double x, double y, int sizeDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp((int) Math.round(x - sizeDp / 2.0));
        lp.topMargin = dp((int) Math.round(y - sizeDp / 2.0));
        return lp;
    }

    private LinearLayout.LayoutParams spacedBox(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    /** Crop: the box shows over the picture and Cancel / Whole / Done replace the dock until one is tapped. */
    private void startCrop() {
        draw.startCrop();
        dock.setVisibility(View.GONE);
        dockTab.setVisibility(View.GONE);
        cropBar.setVisibility(View.VISIBLE);
        status.setText("Drag the corners or edges, or draw a new box. Issue, Send and Save use only what's inside.");
    }

    private void endCrop(boolean keep) {
        draw.endCrop(keep);
        cropBar.setVisibility(View.GONE);
        dock.setVisibility(dockOpen ? View.VISIBLE : View.GONE);
        dockTab.setVisibility(dockOpen ? View.GONE : View.VISIBLE);
        status.setText("");
    }

    /** More: Retake, Crop, Grid, Fit and Clear. */
    private void moreMenu(View anchor) {
        LinearLayout m = column();
        PopupWindow[] w = new PopupWindow[1];
        Row retake = new Row(R.drawable.ic_retake, "Retake");
        Row cropRow = new Row(R.drawable.ic_crop, "Crop");
        cropRow.setActive(draw.isCropped());
        Row gridRow = new Row(R.drawable.ic_grid, "Grid");
        gridRow.setActive(grid > 0);
        Row fitRow = new Row(R.drawable.ic_fit, "Fit");
        Row clear = new Row(R.drawable.ic_trash, "Clear");
        retake.setOnClickListener(v -> { w[0].dismiss(); capture(); });
        cropRow.setOnClickListener(v -> { w[0].dismiss(); startCrop(); });
        gridRow.setOnClickListener(v -> { w[0].dismiss(); gridMenu(anchor); });
        fitRow.setOnClickListener(v -> { w[0].dismiss(); draw.fit(); });
        clear.setOnClickListener(v -> { w[0].dismiss(); draw.clear(); });
        m.addView(retake);
        m.addView(cropRow);
        m.addView(gridRow);
        m.addView(fitRow);
        m.addView(rule(), ruleParams());
        m.addView(clear);
        w[0] = menu(anchor, m, 220);
    }

    /** A marker's note, written in a card right beside it. Its text is the issue. */
    private void markerDialog(DrawView.Marker m, boolean isNew) {
        float[] view = draw.getView();
        LinearLayout card = column();
        card.setBackground(glass(14));
        ((GradientDrawable) card.getBackground()).setColor(0xF2140735);
        card.setPadding(dp(14), dp(10), dp(10), dp(8));
        EditText t = new EditText(this);
        t.setText(m.getText());
        t.setHint("What's wrong here, and what should happen?");
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        t.setMinLines(2);
        t.setMaxLines(6);
        t.setTextSize(17);
        t.setTextColor(TEXT);
        t.setHintTextColor(MUTED);
        t.setBackground(null);
        card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView del = text("DELETE", MUTED, 16);
        del.setTypeface(display());
        del.setLetterSpacing(0.06f);
        del.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView done = text("DONE", TEXT, 16);
        done.setTypeface(display());
        done.setLetterSpacing(0.06f);
        done.setPadding(dp(14), dp(8), dp(14), dp(8));
        done.setBackground(rounded(0x22FFFFFF, 9));
        row.addView(del);
        row.addView(done, spaced(6));
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int w = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(32));
        PopupWindow pop = new PopupWindow(card, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(dp(12));
        pop.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        pop.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        boolean[] deleted = {false};
        pop.setOnDismissListener(() -> {
            if (!deleted[0]) m.setText(t.getText().toString().trim());
            draw.setEditing(null);
            draw.setView(view);
            refreshBar();
        });
        del.setOnClickListener(v -> { deleted[0] = true; pop.dismiss(); draw.removeMarker(m); });
        done.setOnClickListener(v -> pop.dismiss());

        // The keyboard covers the lower half: a low marker slides up first, so it and its card stay in sight, and
        // the picture slides back when the note closes.
        if (draw.screenOf(m)[1] > draw.getHeight() * 0.3f) draw.slideTo(m, draw.getHeight() * 0.2f);
        // Beside the marker: to its right, or to its left near the right edge; kept on screen.
        float[] p = draw.screenOf(m);
        int[] loc = new int[2];
        draw.getLocationInWindow(loc);
        int x = Math.round(p[0] + p[2] + dp(8)), y = Math.max(dp(8), Math.round(p[1] - dp(22)));
        if (x + w > draw.getWidth() - dp(8)) x = Math.round(p[0] - p[2] - dp(8) - w);
        if (x < dp(8)) { // no room either side (a phone): just below the marker
            x = Math.max(dp(8), Math.min(draw.getWidth() - w - dp(8), Math.round(p[0] - w / 2f)));
            y = Math.round(p[1] + p[2] + dp(8));
        }
        draw.setEditing(m);
        pop.showAtLocation(draw, Gravity.NO_GRAVITY, loc[0] + x, loc[1] + y);
        t.requestFocus();
        t.setSelection(t.getText().length());
    }

    // ---- menus

    private PopupWindow menu(View anchor, LinearLayout content, int widthDp) {
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = rounded(RAISED, 12);
        bg.setStroke(dp(1), LINE);
        // Fixed width, so rows can fill it and values sit on the right edge.
        PopupWindow w = new PopupWindow(content, widthDp == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        w.setBackgroundDrawable(bg);
        w.setElevation(dp(12));
        w.showAsDropDown(anchor, 0, dp(8));
        return w;
    }

    private void toolMenu(View anchor) {
        LinearLayout m = column();
        Row[] rows = new Row[TOOL_NAMES.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Row(TOOL_ICONS[i], TOOL_NAMES[i]);
            m.addView(rows[i]);
        }

        // Size and pressure, for the tools that draw lines.
        LinearLayout brush = column();
        brush.addView(rule(), ruleParams());
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(4), dp(12), 0);
        TextView value = text("", MUTED, 15);
        value.setFontFeatureSettings("tnum");
        head.addView(text("Size", TEXT, 15), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(value);
        brush.addView(head);
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(dp(4), 0, dp(12), 0);
        SeekBar size = new SeekBar(this);
        size.setMin(1);
        size.setMax(40);
        tint(size);
        sizeRow.addView(size, new LinearLayout.LayoutParams(0, dp(44), 1));
        FrameLayout slot = new FrameLayout(this);
        View dotView = new View(this);
        slot.addView(dotView, new FrameLayout.LayoutParams(dp(4), dp(4), Gravity.CENTER));
        sizeRow.addView(slot, new LinearLayout.LayoutParams(dp(60), dp(60)));
        brush.addView(sizeRow);
        LinearLayout pressure = new LinearLayout(this);
        pressure.setGravity(Gravity.CENTER_VERTICAL);
        pressure.setPadding(dp(12), 0, dp(4), 0);
        pressure.setMinimumHeight(dp(44));
        pressure.addView(text("Pen pressure", TEXT, 15), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(draw.getPressure());
        sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, MUTED}));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {0x80FC840D, OUTLINE}));
        sw.setOnCheckedChangeListener((b, on) -> draw.setPressure(on));
        pressure.addView(sw);
        brush.addView(pressure);
        m.addView(brush);

        Runnable show = () -> {
            int t = draw.getTool();
            for (int i = 0; i < rows.length; i++) rows[i].setActive(i == t);
            brush.setVisibility(DrawView.hasSize(t) ? View.VISIBLE : View.GONE);
            pressure.setVisibility(t == DrawView.PEN || t == DrawView.ERASER ? View.VISIBLE : View.GONE);
            int v = Math.round(draw.getSize());
            size.setProgress(v);
            value.setText(String.valueOf(v));
            sizeDot(dotView, v);
        };
        for (int i = 0; i < rows.length; i++) {
            int pick = i;
            rows[i].setOnClickListener(v -> { draw.setTool(pick); show.run(); refreshBar(); });
        }
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                draw.setSize(v);
                value.setText(String.valueOf(v));
                sizeDot(dotView, v);
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        show.run();
        menu(anchor, m, 320);
    }

    private void sizeDot(View dotView, int v) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int t = draw.getTool();
        if (t == DrawView.ERASER) d.setStroke(dp(1), TEXT);
        else d.setColor(t == DrawView.HIGHLIGHTER ? (color & 0x00FFFFFF) | 0x5A000000 : color);
        dotView.setBackground(d);
        int px = Math.max(dp(3), Math.round(v * dp(1) * 1.5f)); // 40 fills the 60dp slot
        ViewGroup.LayoutParams lp = dotView.getLayoutParams();
        lp.width = lp.height = px;
        dotView.setLayoutParams(lp);
    }

    private void colorMenu(View anchor) {
        LinearLayout m = column();
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        PopupWindow[] w = new PopupWindow[1];
        for (int c : COLORS) {
            View sw = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            d.setStroke(dp(c == color ? 3 : 1), c == color ? ACCENT : OUTLINE);
            sw.setBackground(d);
            sw.setOnClickListener(v -> {
                color = c;
                draw.setColor(c); // switches back to the pen from the eraser or lasso
                refreshBar();
                w[0].dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(sw, lp);
        }
        m.addView(row);
        w[0] = menu(anchor, m, 0);
    }

    private void gridMenu(View anchor) {
        LinearLayout m = column();
        Row[] sizes = new Row[GRID_NAMES.length];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = new Row(0, GRID_NAMES[i]);
            m.addView(sizes[i]);
        }
        m.addView(rule(), ruleParams());
        Row lines = new Row(0, "Lines"), dots = new Row(0, "Dots");
        m.addView(lines);
        m.addView(dots);
        Runnable show = () -> {
            for (int i = 0; i < sizes.length; i++) sizes[i].setActive(i == grid);
            lines.setActive(!gridDots);
            dots.setActive(gridDots);
            draw.setGrid(GRID_DIVS[grid], gridDots);
            refreshBar();
        };
        for (int i = 0; i < sizes.length; i++) {
            int pick = i;
            sizes[i].setOnClickListener(v -> { grid = pick; show.run(); });
        }
        lines.setOnClickListener(v -> { gridDots = false; if (grid == 0) grid = 2; show.run(); });
        dots.setOnClickListener(v -> { gridDots = true; if (grid == 0) grid = 2; show.run(); });
        show.run();
        menu(anchor, m, 240);
    }

    // ---- actions

    private void capture() {
        Prefs.Computer pc = prefs.computer();
        if (pc == null) {
            showSettings("Add a computer first: it's where screenshots come from.");
            return;
        }
        busy = true;
        status.setText("Capturing " + pc.name + "…");
        io.execute(() -> {
            try {
                HttpURLConnection c = open(pc, "/api/screen", "GET");
                byte[] png = readAll(c);
                Bitmap shot = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (shot == null) throw new Exception(pc.name + " sent something that isn't a picture");
                runOnUiThread(() -> {
                    busy = false;
                    showDraw(shot, null, "");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    if (drawing) status.setText("Couldn't capture " + pc.name + ": " + e.getMessage());
                    else showStart("Couldn't capture " + pc.name + ": " + e.getMessage()
                            + "\nAre both on the same home network (or Tailscale on, on both), and is the helper running on "
                            + pc.name + "?");
                });
            }
        });
    }

    /** Everything a finished note is made of, read off the screen on the UI thread. */
    private final class Note {
        final Bitmap picture = draw.render(), shot = draw.getShot();
        final String text = legacyNote.trim();
        final StringBuilder markers = new StringBuilder();
        final String ink, name, when;

        Note() {
            String json;
            try {
                json = draw.toJson();
            } catch (Exception e) {
                json = "[]";
            }
            ink = json;
            for (DrawView.Marker m : draw.markers()) {
                markers.append(m.number).append(". ").append(m.getText().isEmpty() ? "(no note)" : m.getText().replace("\n", " ")).append('\n');
            }
            Date now = new Date();
            name = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(now);
            when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now);
        }

        byte[] png() {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            picture.compress(Bitmap.CompressFormat.PNG, 100, buf);
            return buf.toByteArray();
        }

        byte[] md() {
            return ("# Note, " + when + "\n\n" + (text.isEmpty() ? "" : text + "\n\n")
                    + (markers.length() > 0 ? "## Markers\n\n" + markers + "\n" : "")
                    + "![Screen](<" + name + ".png>)\n").getBytes(StandardCharsets.UTF_8);
        }

        /** Download/Issue Mortem and History. Returns what went wrong, or null. */
        String keep(byte[] png, byte[] md) {
            String error = null;
            try {
                saveHere(name + ".png", "image/png", png);
                saveHere(name + ".md", "text/markdown", md);
            } catch (Exception e) {
                error = e.getMessage();
            }
            try {
                history.save(name, shot, picture, ink, text);
            } catch (Exception e) {
                error = (error == null ? "" : error + "; ") + "History: " + e.getMessage();
            }
            return error;
        }
    }

    private void buttons(boolean on) {
        send.setEnabled(on);
        save.setEnabled(on);
        issue.setEnabled(on);
    }

    /** Send (folder = AI Inbox) or Save (folder = Saved Notes): the computer's copy goes to that folder, this device's copy to Download/Issue Mortem, and an editable copy to History. */
    private void finish(String folder, String done) {
        Prefs.Computer pc = prefs.computer();
        buttons(false);
        status.setText(done.equals("Sent") ? "Sending…" : "Saving…");
        Note n = new Note();
        io.execute(() -> {
            byte[] png = n.png(), md = n.md();
            String pcError = null;
            if (pc == null) {
                pcError = "no computer set up";
            } else {
                try {
                    put(pc, folder + n.name + ".png", png);
                    put(pc, folder + n.name + ".md", md);
                } catch (Exception e) {
                    pcError = e.getMessage();
                }
            }
            String here = n.keep(png, md), there = pcError;
            runOnUiThread(() -> {
                String where = pc == null ? "the computer" : pc.name;
                if (there == null && here == null) {
                    Toast.makeText(this, done + " to " + where + " and saved on this device", Toast.LENGTH_SHORT).show();
                    showStart(done + ": " + n.name);
                    return;
                }
                buttons(true);
                status.setText((there == null ? "On " + where + ". " : "Not on " + where + ": " + there + ". ")
                        + (here == null ? "Saved on this device." : "Not saved on this device: " + here));
            });
        });
    }

    // ---- GitHub issue

    private void issueDialog() {
        String first = "";
        for (DrawView.Marker m : draw.markers()) {
            if (!m.getText().trim().isEmpty()) { first = m.getText().trim(); break; }
        }
        if (first.isEmpty()) first = legacyNote.trim();
        if (first.isEmpty()) {
            status.setText("Add a marker and write in it: the issue is made from the markers.");
            return;
        }
        if (prefs.token().isEmpty()) {
            showSettings("Add a GitHub token to make issues.");
            return;
        }
        GitHub gh = new GitHub(prefs.token());
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), 0);

        form.addView(text("Repo", MUTED, 14));
        String[] repo = {prefs.repo()};
        Tool repoBtn = new Tool(0, repo[0].isEmpty() ? "Loading repos…" : repo[0], null);
        repoBtn.chevron();
        repoBtn.icon.setVisibility(View.GONE);
        repoBtn.setGravity(Gravity.CENTER_VERTICAL);
        repoBtn.setBackground(outline());
        form.addView(repoBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView titleLabel = text("Title", MUTED, 14);
        titleLabel.setPadding(0, dp(16), 0, 0);
        form.addView(titleLabel);
        EditText title = new EditText(this);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setTextSize(18);
        title.setTextColor(TEXT);
        if (first.contains("\n")) first = first.substring(0, first.indexOf('\n'));
        if (first.length() > 80) first = first.substring(0, 80).trim() + "…";
        title.setText(first);
        title.setHint("Short summary");
        form.addView(title);

        TextView labelsLabel = text("Labels", MUTED, 14);
        labelsLabel.setPadding(0, dp(16), 0, dp(4));
        form.addView(labelsLabel);
        HorizontalScrollView labelScroll = new HorizontalScrollView(this);
        LinearLayout labelRow = new LinearLayout(this);
        labelScroll.addView(labelRow);
        form.addView(labelScroll);
        List<String> picked = new ArrayList<>();

        TextView about = text("The description is the numbered markers and the picture. The title starts as the first marker's note.", MUTED, 14);
        about.setPadding(0, dp(16), 0, 0);
        form.addView(about);

        Runnable loadLabels = () -> {
            labelRow.removeAllViews();
            picked.clear();
            if (repo[0].isEmpty()) return;
            String r = repo[0];
            io.execute(() -> {
                List<String> labels;
                try {
                    labels = gh.labels(r);
                } catch (Exception e) {
                    labels = new ArrayList<>();
                }
                List<String> got = labels;
                runOnUiThread(() -> {
                    if (!r.equals(repo[0])) return;
                    if (got.isEmpty()) labelRow.addView(text("None", MUTED, 15));
                    for (String l : got) {
                        Row chip = new Row(0, l);
                        chip.setOnClickListener(v -> {
                            boolean on = !picked.contains(l);
                            if (on) picked.add(l);
                            else picked.remove(l);
                            chip.setActive(on);
                        });
                        labelRow.addView(chip, spaced(labelRow.getChildCount() == 0 ? 0 : 4));
                    }
                });
            });
        };
        loadLabels.run();

        List<String> repos = new ArrayList<>();
        io.execute(() -> {
            try {
                List<String> got = gh.repos();
                runOnUiThread(() -> {
                    repos.addAll(got);
                    if (repo[0].isEmpty() && !got.isEmpty()) {
                        repo[0] = got.get(0);
                        loadLabels.run();
                    }
                    repoBtn.set(0, repo[0].isEmpty() ? "No repos found" : repo[0]);
                });
            } catch (Exception e) {
                runOnUiThread(() -> repoBtn.set(0, "Couldn't load repos: " + e.getMessage()));
            }
        });
        repoBtn.setOnClickListener(v -> repoMenu(v, repos, r -> {
            repo[0] = r;
            repoBtn.set(0, r);
            loadLabels.run();
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("New GitHub issue")
                .setView(scroll)
                .setPositiveButton("Create issue", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = title.getText().toString().trim();
            if (repo[0].isEmpty()) {
                Toast.makeText(this, "Pick a repo first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (t.isEmpty()) {
                title.setError("An issue needs a title");
                return;
            }
            d.dismiss();
            makeIssue(gh, repo[0], t, new ArrayList<>(picked));
        }));
        d.show();
    }

    interface Pick {
        void picked(String repo);
    }

    /** Repo list with a filter box, newest pushed first. */
    private void repoMenu(View anchor, List<String> repos, Pick pick) {
        LinearLayout m = column();
        EditText filter = new EditText(this);
        filter.setHint("Filter");
        filter.setSingleLine(true);
        filter.setTextColor(TEXT);
        filter.setHintTextColor(MUTED);
        m.addView(filter);
        LinearLayout list = column();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        m.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        PopupWindow[] w = new PopupWindow[1];
        Runnable fill = () -> {
            list.removeAllViews();
            String f = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String r : repos) {
                if (!f.isEmpty() && !r.toLowerCase(Locale.ROOT).contains(f)) continue;
                Row row = new Row(0, r);
                row.setOnClickListener(v -> { w[0].dismiss(); pick.picked(r); });
                list.addView(row);
            }
            if (list.getChildCount() == 0) list.addView(text(repos.isEmpty() ? "Still loading…" : "No match", MUTED, 15));
        };
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int n) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        fill.run();
        w[0] = menu(anchor, m, Math.min(420, getResources().getConfiguration().screenWidthDp - 48));
    }

    /** Picture to the repo's issue-images branch, then the issue that shows it; this device keeps a copy as usual. */
    private void makeIssue(GitHub gh, String repo, String title, List<String> labels) {
        prefs.setRepo(repo);
        buttons(false);
        status.setText("Making the issue…");
        Note n = new Note();
        io.execute(() -> {
            byte[] png = n.png(), md = n.md();
            GitHub.Issue made = null;
            String error = null;
            try {
                String url = gh.upload(repo, "issue-mortem/" + n.name.replace(' ', '_') + ".png", png);
                StringBuilder body = new StringBuilder();
                if (!n.text.isEmpty()) body.append(n.text).append("\n\n");
                if (n.markers.length() > 0) body.append("### Markers\n\n").append(n.markers).append('\n');
                body.append("![Screenshot with markup](").append(url).append(")\n");
                made = gh.createIssue(repo, title, body.toString(), labels);
            } catch (Exception e) {
                error = e.getMessage();
            }
            String here = n.keep(png, md), there = error;
            GitHub.Issue issue = made;
            runOnUiThread(() -> {
                if (issue == null) {
                    buttons(true);
                    status.setText("Issue not made: " + there + (here == null ? "" : ". Not saved on this device: " + here));
                    return;
                }
                showStart("Issue #" + issue.number + " in " + repo + (here == null ? "" : "\nNot saved on this device: " + here));
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Issue #" + issue.number + " made")
                        .setMessage(issue.url)
                        .setPositiveButton("Open", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(issue.url))))
                        .setNegativeButton("Done", null)
                        .show();
            });
        });
    }

    // ---- settings

    /** Kept while Settings is open from the drawing page, so Back returns to the drawing untouched. */
    private LinearLayout drawPage, settingsFrom;

    private void showSettings(String message) {
        settingsFrom = drawing ? drawPage : null;
        drawing = false;
        onHistory = false;
        onSettings = true;
        LinearLayout page = column();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(BG);
        bar.addView(new Tool(R.drawable.ic_back, null, v -> back()));
        TextView title = title("Settings", 26, 0.12f);
        title.setPadding(dp(8), 0, dp(24), 0);
        bar.addView(title);
        bar.addView(new Wave(this), new LinearLayout.LayoutParams(0, dp(56), 1));
        page.addView(bar);
        page.addView(rule());

        LinearLayout body = column();
        body.setPadding(dp(32), dp(24), dp(32), dp(32));
        if (message != null) {
            TextView m = text(message, ACCENT, 16);
            m.setPadding(0, 0, 0, dp(16));
            body.addView(m);
        }

        body.addView(section("Computers"));
        TextView help = text("Each computer runs the Issue Mortem helper. Sync finds them on your home network or over Tailscale, or type the address and key its window shows.", MUTED, 15);
        help.setPadding(0, dp(4), 0, dp(12));
        body.addView(help);
        List<Prefs.Computer> computers = prefs.computers();
        for (Prefs.Computer c : computers) {
            if (Sync.homeOnly(c.address)) {
                TextView away = text(AWAY, MUTED, 15);
                away.setPadding(0, 0, 0, dp(12));
                body.addView(away);
                break;
            }
        }
        for (int i = 0; i < computers.size(); i++) {
            Prefs.Computer c = computers.get(i);
            int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setMinimumHeight(dp(72));
            ImageView screen = new ImageView(this);
            screen.setImageResource(R.drawable.ic_monitor);
            screen.setColorFilter(MUTED);
            row.addView(screen, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout names = column();
            names.setPadding(dp(16), 0, dp(12), 0);
            names.addView(text(c.name, TEXT, 17));
            TextView result = text(c.address, MUTED, 14);
            result.setSingleLine(true);
            result.setEllipsize(TextUtils.TruncateAt.MIDDLE); // relay addresses are long
            names.addView(result);
            row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView online = text("Checking…", MUTED, 14);
            if (getResources().getConfiguration().screenWidthDp < 600) {
                names.addView(online); // phones: under the address, so the name keeps its room
            } else {
                online.setPadding(0, 0, dp(16), 0);
                row.addView(online);
            }
            checkOnline(c, online);
            row.addView(pill("Test", false, v -> testComputer(c, result)));
            row.addView(pill("Edit", false, v -> computerDialog(at)), spaced(8));
            body.addView(row);
            if (i < computers.size() - 1) body.addView(rule(), ruleParams());
        }
        LinearLayout addRow = new LinearLayout(this);
        addRow.setPadding(0, dp(8), 0, 0);
        addRow.setPadding(0, dp(16), 0, 0);
        addRow.addView(pill(R.drawable.ic_sync, "Sync", true, v -> syncDialog()));
        addRow.addView(pill("Add computer", false, v -> computerDialog(-1)), spaced(12));
        body.addView(addRow);

        View gap = rule();
        body.addView(gap, ruleParams());
        ((LinearLayout.LayoutParams) gap.getLayoutParams()).setMargins(0, dp(28), 0, dp(24));

        body.addView(section("GitHub"));
        TextView ghHelp = text("A fine-grained token with Contents and Issues set to Read and write, for the repos you want issues in.", MUTED, 15);
        ghHelp.setPadding(0, dp(4), 0, dp(12));
        body.addView(ghHelp);
        EditText token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("github_pat_…");
        token.setTextColor(TEXT);
        token.setHintTextColor(MUTED);
        token.setText(prefs.token());
        body.addView(token);
        TextView ghResult = text("", MUTED, 15);
        LinearLayout ghRow = new LinearLayout(this);
        ghRow.setGravity(Gravity.CENTER_VERTICAL);
        ghRow.setPadding(0, dp(8), 0, 0);
        ghRow.addView(pill("Save and check", false, v -> {
            prefs.setToken(token.getText().toString());
            ghResult.setText("Checking…");
            String t = prefs.token();
            io.execute(() -> {
                String msg;
                try {
                    msg = "Signed in as " + new GitHub(t).login();
                } catch (Exception e) {
                    msg = "Not working: " + e.getMessage();
                }
                String shown = msg;
                runOnUiThread(() -> ghResult.setText(shown));
            });
        }));
        ghRow.addView(pill("Make a token", false, v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/settings/personal-access-tokens/new")))), spaced(12));
        body.addView(ghRow);
        ghResult.setPadding(0, dp(12), 0, 0);
        body.addView(ghResult);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setPage(page);
    }

    /** Accent-tinted section title with a rule under it. */
    private View section(String title) {
        LinearLayout s = column();
        TextView t = title(title, 20, 0.1f);
        s.addView(t);
        View r = rule();
        s.addView(r, ruleParams());
        ((LinearLayout.LayoutParams) r.getLayoutParams()).setMargins(0, dp(8), 0, 0);
        return s;
    }

    /** Online (green dot), Wrong key or Offline next to a computer in Settings. */
    private void checkOnline(Prefs.Computer c, TextView label) {
        new Thread(() -> {
            int code;
            try {
                HttpURLConnection h = open(c, "/api/hello", "GET");
                h.setConnectTimeout(4000);
                code = h.getResponseCode();
                h.disconnect();
            } catch (Exception e) {
                code = 0;
            }
            int got = code;
            runOnUiThread(() -> {
                label.setText(got == 200 ? "●  Online" : got == 401 ? "✕  Wrong key" : "○  Offline");
                label.setTextColor(got == 200 ? ONLINE : got == 401 ? ACCENT : MUTED);
            });
        }).start();
    }

    private void testComputer(Prefs.Computer c, TextView result) {
        result.setText("Testing…");
        io.execute(() -> {
            String msg;
            try {
                HttpURLConnection h = open(c, "/api/hello", "GET");
                msg = "Working: " + new org.json.JSONObject(new String(readAll(h), StandardCharsets.UTF_8)).optString("name", c.address);
            } catch (Exception e) {
                msg = "Not working: " + e.getMessage();
            }
            String shown = msg;
            runOnUiThread(() -> result.setText(shown));
        });
    }

    static final String AWAY = "Without Tailscale this works on your home network only. To use it away from home, "
            + "use Tailscale or your own server (an Issue Mortem relay; see the helper's window).";

    /** Sync: listen for a minute while the helpers on the person's computers offer themselves. */
    private void syncDialog() {
        TextView log = text("Looking for computers on your home network and Tailscale…\n\nEach one needs the Issue "
                + "Mortem helper running. Nothing to do on the computer.", TEXT, 16);
        log.setPadding(dp(24), dp(16), dp(24), dp(8));
        Sync sync = new Sync();
        int[] found = {0};
        boolean[] warned = {false};
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sync")
                .setView(log)
                .setNegativeButton("Done", null)
                .create();
        d.setOnDismissListener(x -> {
            sync.stop();
            showSettings(null);
        });
        d.show();
        d.getWindow().setBackgroundDrawable(rounded(RAISED, 16));
        sync.start(new Sync.Listener() {
            @Override
            public void found(Prefs.Computer c) {
                runOnUiThread(() -> {
                    found[0]++;
                    boolean added = keepComputer(c);
                    log.append("\n\n" + (added ? "Added " : "Updated ") + c.name + " (" + c.address + ")");
                    if (Sync.homeOnly(c.address) && !warned[0]) {
                        warned[0] = true;
                        log.append("\n" + AWAY);
                    }
                });
            }

            @Override
            public void done(String error) {
                runOnUiThread(() -> {
                    if (error != null) log.append("\n\n" + error);
                    else if (found[0] == 0)
                        log.append("\n\nNo computers found. Is the helper installed on the computer, and are both on the "
                                + "same home network (or Tailscale on, on both)?");
                    else log.append("\n\nDone.");
                });
            }
        });
    }

    /** Adds a synced computer, or updates the one with the same address. True when it's new. */
    private boolean keepComputer(Prefs.Computer c) {
        List<Prefs.Computer> list = prefs.computers();
        for (Prefs.Computer old : list) {
            if (old.address.trim().equalsIgnoreCase(c.address)) {
                old.name = c.name;
                old.key = c.key;
                prefs.setComputers(list);
                return false;
            }
        }
        list.add(c);
        prefs.setComputers(list);
        if (list.size() == 1) prefs.setCurrent(0);
        return true;
    }

    /** Add (at = -1) or edit a computer. */
    private void computerDialog(int at) {
        List<Prefs.Computer> list = prefs.computers();
        Prefs.Computer c = at < 0 ? new Prefs.Computer("", "", "") : list.get(at);
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = field(form, "Name", "Desk PC", c.name);
        EditText address = field(form, "Address", "192.168.1.20:8642", c.address);
        EditText key = field(form, "Key", "from the helper's window", c.key);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(at < 0 ? "Add computer" : "Edit computer")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    c.name = name.getText().toString().trim();
                    c.address = address.getText().toString().trim();
                    c.key = key.getText().toString().trim();
                    if (c.name.isEmpty()) c.name = c.address;
                    if (c.address.isEmpty()) return;
                    if (at < 0) {
                        list.add(c);
                        prefs.setComputers(list);
                        prefs.setCurrent(list.size() - 1);
                    } else {
                        prefs.setComputers(list);
                    }
                    showSettings(null);
                })
                .setNegativeButton("Cancel", null);
        if (at >= 0) b.setNeutralButton("Remove", (d, w) -> {
            list.remove(at);
            prefs.setComputers(list);
            showSettings(null);
        });
        b.show();
    }

    private EditText field(LinearLayout form, String label, String hint, String value) {
        TextView l = text(label, MUTED, 14);
        l.setPadding(0, dp(12), 0, 0);
        form.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(18);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setText(value);
        form.addView(e);
        return e;
    }

    // ---- network and files

    /** A request to one of the person's computers. The helper wants its key on every request. */
    private HttpURLConnection open(Prefs.Computer pc, String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(pc.url() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("X-Key", pc.key);
        return c;
    }

    private void put(Prefs.Computer pc, String path, byte[] body) throws Exception {
        String q = URLEncoder.encode(path, "UTF-8").replace("+", "%20");
        HttpURLConnection c = open(pc, "/api/file?path=" + q, "PUT");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body);
        }
        readAll(c);
    }

    private static byte[] readAll(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            in.close();
        }
        if (code == 401) throw new Exception("wrong key (check it in the helper's window)");
        if (code >= 400) throw new Exception("HTTP " + code + " " + out.toString("UTF-8"));
        return out.toByteArray();
    }

    /** Download/Issue Mortem on this device. */
    private void saveHere(String name, String type, byte[] data) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, type);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Issue Mortem");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("couldn't make " + name);
        try (OutputStream o = getContentResolver().openOutputStream(uri)) {
            o.write(data);
        }
    }

    // ---- toolbar pieces

    /** A 44dp toolbar button: icon, optional label, optional chevron (opens a menu). */
    private class Tool extends LinearLayout {
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView label = new TextView(MainActivity.this);
        ImageView chevron;
        boolean active;

        Tool(int iconRes, String text, View.OnClickListener click) {
            super(MainActivity.this);
            setGravity(Gravity.CENTER);
            setMinimumWidth(dp(44));
            setPadding(dp(10), 0, dp(text == null ? 10 : 14), 0);
            setBackground(ripple());
            setOnClickListener(click);
            addView(icon, new LayoutParams(dp(22), dp(22)));
            if (iconRes != 0) icon.setImageResource(iconRes);
            label.setTextSize(15);
            if (text != null) label.setText(text);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            addView(label, lp);
            label.setVisibility(text == null ? GONE : VISIBLE);
            setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            paint();
        }

        void chevron() {
            chevron = new ImageView(MainActivity.this);
            chevron.setImageResource(R.drawable.ic_chevron);
            LayoutParams lp = new LayoutParams(dp(18), dp(18));
            lp.leftMargin = dp(4);
            addView(chevron, lp);
            setPadding(getPaddingLeft(), 0, dp(8), 0);
            paint();
        }

        void set(int iconRes, String text) {
            icon.setImageResource(iconRes);
            label.setText(text);
            paint();
        }

        void dot(int c) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            d.setStroke(dp(1), OUTLINE);
            icon.setImageDrawable(d);
            icon.clearColorFilter();
        }

        void setActive(boolean on) {
            active = on;
            paint();
        }

        @Override
        public void setEnabled(boolean on) {
            super.setEnabled(on);
            setAlpha(on ? 1f : 0.35f);
        }

        private void paint() {
            int c = active ? ACCENT : TEXT;
            icon.setColorFilter(icon.getDrawable() instanceof GradientDrawable ? null : new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
            label.setTextColor(c);
            if (chevron != null) chevron.setColorFilter(MUTED);
        }
    }

    /** A 44dp menu row; the chosen one gets the accent. */
    private class Row extends LinearLayout {
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView label;

        Row(int iconRes, String text) {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(44));
            setPadding(dp(12), 0, dp(16), 0);
            if (iconRes != 0) {
                icon.setImageResource(iconRes);
                LayoutParams lp = new LayoutParams(dp(22), dp(22));
                lp.rightMargin = dp(12);
                addView(icon, lp);
            }
            label = text(text, TEXT, 15);
            addView(label);
            setActive(false);
        }

        void setActive(boolean on) {
            int c = on ? ACCENT : TEXT;
            label.setTextColor(c);
            icon.setColorFilter(c);
            GradientDrawable bg = rounded(on ? ACTIVE_BG : Color.TRANSPARENT, 8);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        }
    }

    private TextView pill(String text, boolean primary, View.OnClickListener click) {
        TextView b = text(text.toUpperCase(Locale.ROOT), TEXT, 18);
        b.setTypeface(display());
        b.setLetterSpacing(0.06f);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(104));
        b.setMinHeight(dp(48));
        b.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable bg = rounded(Color.TRANSPARENT, 10);
        if (primary) {
            bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            bg.setColors(new int[] {Wave.FIRE[0], Wave.FIRE[1], Wave.FIRE[2]});
        } else {
            bg.setStroke(dp(1), OUTLINE);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setOnClickListener(click);
        return b;
    }

    /** A pill with an icon before its text. */
    private TextView pill(int iconRes, String text, boolean primary, View.OnClickListener click) {
        TextView b = pill(text, primary, click);
        android.graphics.drawable.Drawable d = getDrawable(iconRes).mutate();
        d.setBounds(0, 0, dp(20), dp(20));
        d.setTint(TEXT);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        return b;
    }

    private android.graphics.Typeface display;

    /** Barlow Condensed SemiBold: titles and buttons. */
    private android.graphics.Typeface display() {
        if (display == null) display = getResources().getFont(R.font.barlow_condensed_semibold);
        return display;
    }

    /** A title in the condensed uppercase face, spaced wide. */
    private TextView title(String s, int sp, float spacing) {
        TextView t = text(s.toUpperCase(Locale.ROOT), TEXT, sp);
        t.setTypeface(display());
        t.setLetterSpacing(spacing);
        return t;
    }

    /** The raven over the moon. */
    private ImageView logo(int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(R.mipmap.ic_launcher);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(fill);
        return d;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, 10));
    }

    private GradientDrawable outline() {
        GradientDrawable d = rounded(Color.TRANSPARENT, 10);
        d.setStroke(dp(1), OUTLINE);
        return d;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(28));
        lp.setMargins(dp(12), 0, dp(12), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private LinearLayout.LayoutParams ruleParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private LinearLayout.LayoutParams spaced(int leftDp) {
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    private void tint(SeekBar b) {
        b.setProgressTintList(ColorStateList.valueOf(ACCENT));
        b.setThumbTintList(ColorStateList.valueOf(ACCENT));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
    }

    // ---- layout helpers

    private void setPage(LinearLayout page) {
        // Android 15 draws apps edge to edge: pad the page clear of the system bars and the keyboard. The drawing
        // page stays its size under the keyboard (the picture slides instead, see markerDialog).
        page.setOnApplyWindowInsetsListener((v, in) -> {
            int types = WindowInsets.Type.systemBars() | (page == drawPage ? 0 : WindowInsets.Type.ime());
            android.graphics.Insets i = in.getInsets(types);
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        page.setBackgroundColor(CANVAS);
        setContentView(page);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
