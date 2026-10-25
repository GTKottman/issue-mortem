package app.issuemortem;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the person set up, kept in the app's private settings: their computers (each running the Issue Mortem helper)
 * and their GitHub token, plus which computer and repo they used last.
 */
public class Prefs {
    public static final class Computer {
        public String name, address, key;

        public Computer(String name, String address, String key) {
            this.name = name;
            this.address = address;
            this.key = key;
        }

        /** "100.101.102.103:8642" or "my-pc.example.ts.net:8642" become a full http:// URL. */
        public String url() {
            String a = address.trim();
            while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
            return a.startsWith("http://") || a.startsWith("https://") ? a : "http://" + a;
        }
    }

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public List<Computer> computers() {
        List<Computer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("computers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Computer(o.optString("name"), o.optString("address"), o.optString("key")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setComputers(List<Computer> list) {
        JSONArray a = new JSONArray();
        try {
            for (Computer c : list) a.put(new JSONObject().put("name", c.name).put("address", c.address).put("key", c.key));
        } catch (Exception ignored) {
        }
        p.edit().putString("computers", a.toString()).apply();
        if (current() >= list.size()) setCurrent(Math.max(0, list.size() - 1));
    }

    /** The computer captures come from, or null when none is set up. */
    public Computer computer() {
        List<Computer> list = computers();
        return list.isEmpty() ? null : list.get(Math.min(current(), list.size() - 1));
    }

    public int current() {
        return p.getInt("current", 0);
    }

    public void setCurrent(int i) {
        p.edit().putInt("current", i).apply();
    }

    public String token() {
        return p.getString("token", "");
    }

    public void setToken(String t) {
        p.edit().putString("token", t.trim()).apply();
    }

    /** Last repo an issue went to, "owner/name". */
    public String repo() {
        return p.getString("repo", "");
    }

    public void setRepo(String r) {
        p.edit().putString("repo", r).apply();
    }
}
