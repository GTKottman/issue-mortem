package app.issuemortem;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The few GitHub REST calls the app needs, with the person's own token. Runs on a background thread.
 *
 * GitHub has no API for attaching a picture to an issue, so pictures are committed to a branch of the same repo
 * (BRANCH, made empty on first use so it never mixes with the code) and the issue links to them there. Private repos
 * stay private: the picture only shows for people who can see the repo.
 */
public class GitHub {
    static final String API = "https://api.github.com";
    static final String BRANCH = "issue-images";

    public static final class Issue {
        public final int number;
        public final String url;

        Issue(int number, String url) {
            this.number = number;
            this.url = url;
        }
    }

    private final String token;

    public GitHub(String token) {
        this.token = token;
    }

    /** The account the token belongs to. */
    public String login() throws Exception {
        return ((JSONObject) call("GET", "/user", null)).getString("login");
    }

    /** Repos the token can push to, most recently pushed first. */
    public List<String> repos() throws Exception {
        List<String> out = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JSONArray a = (JSONArray) call("GET", "/user/repos?per_page=100&sort=pushed&page=" + page, null);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                JSONObject perm = r.optJSONObject("permissions");
                if (r.optBoolean("has_issues", true) && !r.optBoolean("archived") && (perm == null || perm.optBoolean("push")))
                    out.add(r.getString("full_name"));
            }
            if (a.length() < 100) break;
        }
        return out;
    }

    public List<String> labels(String repo) throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray a = (JSONArray) call("GET", "/repos/" + repo + "/labels?per_page=100", null);
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("name"));
        return out;
    }

    /** Commits the picture to BRANCH and returns a link that shows it inside an issue. */
    public String upload(String repo, String path, byte[] png) throws Exception {
        ensureBranch(repo);
        JSONObject body = new JSONObject()
                .put("message", "Picture for an issue")
                .put("branch", BRANCH)
                .put("content", Base64.encodeToString(png, Base64.NO_WRAP));
        call("PUT", "/repos/" + repo + "/contents/" + path, body);
        return "https://github.com/" + repo + "/blob/" + BRANCH + "/" + path + "?raw=true";
    }

    public Issue createIssue(String repo, String title, String text, List<String> labels) throws Exception {
        JSONObject body = new JSONObject().put("title", title).put("body", text);
        if (!labels.isEmpty()) body.put("labels", new JSONArray(labels));
        JSONObject r = (JSONObject) call("POST", "/repos/" + repo + "/issues", body);
        return new Issue(r.getInt("number"), r.getString("html_url"));
    }

    /** First upload to a repo: make BRANCH as a commit with no parent, holding only a README. */
    private void ensureBranch(String repo) throws Exception {
        try {
            call("GET", "/repos/" + repo + "/git/ref/heads/" + BRANCH, null);
            return;
        } catch (Status s) {
            if (s.code != 404) throw s;
        }
        JSONObject readme = new JSONObject().put("path", "README.md").put("mode", "100644").put("type", "blob")
                .put("content", "Pictures for this repo's issues, added by Issue Mortem. Safe to ignore; don't merge.\n");
        String tree = ((JSONObject) call("POST", "/repos/" + repo + "/git/trees",
                new JSONObject().put("tree", new JSONArray().put(readme)))).getString("sha");
        String commit = ((JSONObject) call("POST", "/repos/" + repo + "/git/commits",
                new JSONObject().put("message", "Pictures for issues").put("tree", tree).put("parents", new JSONArray()))).getString("sha");
        call("POST", "/repos/" + repo + "/git/refs", new JSONObject().put("ref", "refs/heads/" + BRANCH).put("sha", commit));
    }

    static final class Status extends Exception {
        final int code;

        Status(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private Object call(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (body != null) {
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(b.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
        }
        String text = out.toString("UTF-8");
        if (code >= 400) {
            String msg = text;
            try {
                msg = new JSONObject(text).optString("message", text);
            } catch (Exception ignored) {
            }
            if (code == 401) msg = "GitHub didn't accept the token (" + msg + ")";
            else if (code == 403 || code == 404) msg = msg + " (does the token have access to this repo, with Contents and Issues set to Read and write?)";
            throw new Status(code, msg);
        }
        if (text.isEmpty()) return new JSONObject();
        return text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
    }
}
