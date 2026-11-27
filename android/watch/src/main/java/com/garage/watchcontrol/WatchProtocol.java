package com.garage.watchcontrol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WatchProtocol {
    static final int PROTOCOL = 1;
    static final String COMPACT_EXECUTE_ACK = "compact_execute_ack";
    static final String CONTROL_ICONS_FEATURE = "control_icons";
    static final int HOME_ACTIONS = 4;
    static final String MIN_PHONE_APP = "0.1.14";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private static final Map<String, String> ACTION_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> SWITCH_LABELS = new LinkedHashMap<>();

    static {
        ACTION_LABELS.put("lock", "车锁");
        ACTION_LABELS.put("climate", "空调");
        ACTION_LABELS.put("window_vent", "窗微开");
        ACTION_LABELS.put("trunk", "后备箱");
        ACTION_LABELS.put("find", "寻车");
        ACTION_LABELS.put("window_open", "窗全开");
        ACTION_LABELS.put("heat", "快升温");
        ACTION_LABELS.put("cool", "快降温");
        ACTION_LABELS.put("purify", "净化");
        ACTION_LABELS.put("defrost", "除霜");
        ACTION_LABELS.put("sentry", "哨兵");

        ACTION_LABELS.put("lynk_unlock", "功能1");
        ACTION_LABELS.put("lynk_window", "功能2");
        ACTION_LABELS.put("lynk_kjck", "功能3");
        ACTION_LABELS.put("lynk_ccqk", "功能4");
        SWITCH_LABELS.put("climate", "空调");
        SWITCH_LABELS.put("heat", "升温");
        SWITCH_LABELS.put("cool", "降温");
        SWITCH_LABELS.put("purify", "净化");
        SWITCH_LABELS.put("defrost", "除霜");
        SWITCH_LABELS.put("sentry", "哨兵");
    }

    private WatchProtocol() {}

    static int controlColumns(boolean round, int width, int height) {
        int shortSide = Math.min(width, height);
        return round || Math.abs(width - height) <= Math.max(2, shortSide / 50) ? 2 : 1;
    }

    static int controlRowColumns(int columns, int actionCount) {
        return columns == 2 && actionCount == 3 ? 3 : columns;
    }

    record WatchScale(boolean roundScreen, int diameterPx, float density) {
        static final float DESIGN_WIDTH = 336.0f;

        int px(int design336, int rectDp) {
            if (!roundScreen) return Math.round(rectDp * density);
            int safeDial = diameterPx > 0 ? diameterPx : 466;
            return Math.max(1, Math.round(safeDial * (design336 / DESIGN_WIDTH)));
        }

        float textPx(int design336) {
            int safeDial = diameterPx > 0 ? diameterPx : 466;
            return safeDial * (design336 / DESIGN_WIDTH);
        }

        int roundOrb336(int actionCount) {
            return actionCount <= 1 ? 64 : actionCount == 2 ? 52 : actionCount == 3 ? 38 : 42;
        }

        int roundIcon336(int actionCount) {
            return actionCount <= 1 ? 52 : actionCount == 2 ? 42 : actionCount == 3 ? 30 : 34;
        }

        int roundLabel336(int actionCount) {
            return actionCount <= 1 ? 18 : actionCount == 2 ? 16 : actionCount == 3 ? 13 : 14;
        }

        static int roundControlsEstimatedHeight336(int actionCount, boolean withHint) {
            int orb = actionCount <= 1 ? 56 : actionCount == 2 ? 46 : actionCount == 3 ? 34 : 38;
            int labelLine = actionCount <= 1 ? 21 : actionCount == 2 ? 19 : actionCount == 3 ? 16 : 17;
            int rowHeight = orb + 10 + labelLine + (withHint ? 14 : 0);
            int rows = actionCount <= 3 ? 1 : 2;
            int gridHeight = rows * rowHeight + (rows > 1 ? 8 : 0);
            // top/bottom padding (14 + 14) + topPill (~26) + settingsPill (~36) + margins (~8)
            return 90 + gridHeight;
        }
    }

    record RoundHomeMetrics(
            int hPadDp,
            int topPadDp,
            int bottomPadDp,
            int modelMaxWidthDp,
            int modelSp,
            int heroSp,
            int subrangeSp,
            int statusTextSp,
            int statusDotDp,
            int statusIconDp,
            int statusHPadDp,
            int statusVPadDp,
            int splitTextSp,
            int splitIconDp,
            int splitBoxHPadDp,
            int splitBoxVPadDp,
            int splitGapDp,
            int sectionGapDp) {
        static RoundHomeMetrics forDiameterDp(int dialDp) {
            int safeDial = dialDp > 0 ? dialDp : 233;
            float scale = Math.max(0.76f, Math.min(1.42f, safeDial / 233.0f));
            return new RoundHomeMetrics(
                    clamp(Math.round(22 * scale), 16, 32),
                    clamp(Math.round(16 * scale), 12, 24),
                    clamp(Math.round(12 * scale), 8, 18),
                    Math.round(safeDial * 0.58f),
                    clamp(Math.round(14 * scale), 12, 20),
                    clamp(Math.round(40 * scale), 30, 56),
                    clamp(Math.round(11 * scale), 9, 16),
                    clamp(Math.round(11 * scale), 9, 16),
                    clamp(Math.round(6 * scale), 5, 9),
                    clamp(Math.round(12 * scale), 10, 17),
                    clamp(Math.round(10 * scale), 8, 14),
                    clamp(Math.round(4 * scale), 3, 6),
                    clamp(Math.round(14 * scale), 11, 20),
                    clamp(Math.round(14 * scale), 11, 20),
                    clamp(Math.round(6 * scale), 4, 9),
                    clamp(Math.round(4 * scale), 3, 6),
                    clamp(Math.round(6 * scale), 4, 9),
                    clamp(Math.round(4 * scale), 2, 7));
        }

        static RoundHomeMetrics rectDefault() {
            return new RoundHomeMetrics(
                    14, 14, 14, 0,
                    14, 40, 11, 11, 6, 12, 10, 4,
                    14, 14, 6, 4, 6, 4);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    static JSONObject buildRequest(String id, String type, String action, String token,
                                   String controlDirection, String watchVersion) throws JSONException {
        JSONObject request = new JSONObject()
                .put("v", PROTOCOL)
                .put("id", id)
                .put("type", type);
        if ("hello".equals(type)) {
            request.put("bandApp", watchVersion).put("minPhoneApp", MIN_PHONE_APP);
        }
        if (action != null && !action.isEmpty()) request.put("action", action);
        if (token != null && !token.isEmpty()) request.put("token", token);
        if ("prepare".equals(type) && controlDirection != null && !controlDirection.isEmpty()) {
            request.put("controlDirection", controlDirection);
        }
        return request;
    }

    static JSONObject buildCarImageRequest(String id, String hash, int index) throws JSONException {
        return buildChunkRequest(id, "car_image", hash, index);
    }

    static JSONObject buildControlIconRequest(String id, String hash, int index) throws JSONException {
        return buildChunkRequest(id, "control_icon", hash, index);
    }

    private static JSONObject buildChunkRequest(String id, String type, String hash, int index) throws JSONException {
        return new JSONObject()
                .put("v", PROTOCOL)
                .put("id", id)
                .put("type", type)
                .put("hash", hash)
                .put("index", index);
    }

    static boolean isActionActive(String action, String direction, boolean available) {
        return available && ("close".equals(direction) || ("lock".equals(action) && "lock".equals(direction)));
    }

    static String compatibilityMessage(JSONObject reply, String watchVersion) {
        if (reply.optInt("v") != PROTOCOL || reply.optInt("protocol") != PROTOCOL) {
            return "请将手机和手表更新到配套版本";
        }
        int[] phone = versionParts(reply.optString("phoneApp"));
        if (phone == null) return "";
        int[] watch = versionParts(watchVersion);
        int[] minimum = versionParts(MIN_PHONE_APP);
        if (watch != null) {
            if (phone[0] > watch[0]) return "请更新手表端到配套版本";
            if (phone[0] < watch[0]) return "请更新手机端到配套版本";
        }
        if (minimum != null) {
            for (int i = 0; i < minimum.length; i++) {
                if (phone[i] < minimum[i]) return "请更新手机端到配套版本";
                if (phone[i] > minimum[i]) break;
            }
        }
        return "";
    }

    static int[] versionParts(String value) {
        if (value == null) return null;
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) return null;
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }

    static List<String> knownActions(JSONArray ids) {
        List<String> result = new ArrayList<>();
        if (ids == null) return result;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            if (ACTION_LABELS.containsKey(id) && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    static String voiceAction(String brand, String action) {
        return "LYNKCO".equals(brand) && ("lynk_unlock".equals(action) || "lynk_window".equals(action)) ? action : "";
    }

    static boolean isWindow(String action) {
        return "window_vent".equals(action) || "window_open".equals(action);
    }

    static boolean needsDirection(String action, boolean hasDirectionMap) {
        if ("lock".equals(action)) return hasDirectionMap;
        return isWindow(action) || SWITCH_LABELS.containsKey(action);
    }

    static Map<String, String> readControlDirections(JSONObject directions, String lockState) {
        Map<String, String> result = new LinkedHashMap<>();
        if (directions != null) {
            for (String action : ACTION_LABELS.keySet()) {
                String direction = directions.optString(action);
                boolean valid = "lock".equals(action)
                        ? ("lock".equals(direction) || "unlock".equals(direction))
                        : (needsDirection(action, true) && ("open".equals(direction) || "close".equals(direction)));
                if (valid) result.put(action, direction);
            }
        } else if ("locked".equals(lockState) || "unlocked".equals(lockState)) {
            result.put("lock", "locked".equals(lockState) ? "unlock" : "lock");
        }
        return result;
    }

    static String controlLabel(String action, String direction) {
        if ("lock".equals(action)) {
            if ("lock".equals(direction)) return "上锁";
            if ("unlock".equals(direction)) return "解锁";
        }
        if (isWindow(action) && "close".equals(direction)) return "关窗";
        if (SWITCH_LABELS.containsKey(action) && direction != null && !direction.isEmpty()) {
            return ("open".equals(direction) ? "开" : "关") + SWITCH_LABELS.get(action);
        }
        String label = ACTION_LABELS.get(action);
        return label == null ? action : label;
    }

    static String lynkControlLabel(JSONObject labels, String action) {
        if (labels != null && labels.opt(action) instanceof String value) {
            String label = value.trim();
            if (!label.isEmpty() && label.codePointCount(0, label.length()) <= 3) return label;
        }
        return controlLabel(action, null);
    }

    static String controlHint(JSONObject states, String action) {
        if (states == null) return "";
        return switch (states.optString(action)) {
            case "loading" -> "加载中";
            case "unknown" -> "状态未知";
            default -> "";
        };
    }

    static long reconnectDelayMillis(int attempt) {
        return attempt <= 0 ? 0 : 1000L << Math.min(4, attempt - 1);
    }

    static boolean isExecuteAck(JSONObject reply) {
        return reply.optInt("v") == PROTOCOL && "execute_ack".equals(reply.optString("type"))
                && switch (reply.optString("status")) {
                    case "dispatched", "unknown", "blocked", "phone_confirm" -> true;
                    default -> false;
                };
    }

    static String iconAsset(String action, String direction) {
        return iconAsset(action, direction, "");
    }

    static String iconAsset(String action, String direction, String lynkLabel) {
        if ("lock".equals(action) && "lock".equals(direction)) return "lock-open.png";
        if (action.startsWith("lynk_")) return switch (lynkLabel) {
            case "车锁", "解锁" -> "lock.png";
            case "上锁" -> "lock-open.png";
            case "车窗", "窗全开", "关窗" -> "window_open.png";
            case "窗微开" -> "window_vent.png";
            case "后备箱" -> "trunk.png";
            case "寻车" -> "find.png";
            case "快升温" -> "heat.png";
            case "快降温" -> "cool.png";
            case "发动机" -> "lynk-engine.png";
            case "天窗" -> "lynk-sunroof.png";
            case "充电口" -> "lynk-charge-port.png";
            default -> "settings.png";
        };
        return action + ".png";
    }

    static String rangeText(JSONObject vehicle, String key) {
        if (vehicle == null || !vehicle.has(key) || vehicle.isNull(key)) return "—";
        int value = vehicle.optInt(key, -1);
        return value >= 0 ? String.valueOf(value) : "—";
    }

    static int validPercent(JSONObject vehicle, String key) {
        if (vehicle == null || !vehicle.has(key) || vehicle.isNull(key)) return -1;
        int value = vehicle.optInt(key, -1);
        return value >= 0 && value <= 100 ? value : -1;
    }

    private static final String MISSING_ENERGY = "— km";

    record EnergyBreakdown(String electric, String fuel) {
        boolean showElectric() { return !MISSING_ENERGY.equals(electric) || MISSING_ENERGY.equals(fuel); }
        boolean showFuel() { return !MISSING_ENERGY.equals(fuel) || MISSING_ENERGY.equals(electric); }
    }

    static EnergyBreakdown energyBreakdown(JSONObject vehicle) {
        return new EnergyBreakdown(energyDisplay(vehicle, "electricKm", "electricPercent"),
                energyDisplay(vehicle, "fuelKm", "fuelPercent"));
    }

    private static String energyDisplay(JSONObject vehicle, String kmKey, String percentKey) {
        String km = rangeText(vehicle, kmKey);
        if (!"—".equals(km)) return km + " km";
        int percent = validPercent(vehicle, percentKey);
        return percent >= 0 ? percent + "%" : MISSING_ENERGY;
    }

    static String heroDisplay(JSONObject vehicle) {
        String total = rangeText(vehicle, "totalKm");
        if (!"—".equals(total)) return total + " km";
        String electricKm = rangeText(vehicle, "electricKm");
        String fuelKm = rangeText(vehicle, "fuelKm");
        if (!"—".equals(electricKm) && "—".equals(fuelKm)) return electricKm + " km";
        int electricPercent = validPercent(vehicle, "electricPercent");
        int fuelPercent = validPercent(vehicle, "fuelPercent");
        if (electricPercent >= 0 && fuelPercent < 0 && "—".equals(fuelKm)) return electricPercent + "%";
        return "— km";
    }

    static String heroNumber(String heroDisplay) {
        if (heroDisplay == null || heroDisplay.isEmpty()) return "—";
        return heroDisplay.endsWith(" km") ? heroDisplay.substring(0, heroDisplay.length() - 3) : heroDisplay;
    }

    static String heroUnit(String heroDisplay) {
        return heroDisplay != null && heroDisplay.endsWith(" km") ? " km" : "";
    }

    static String singleScreenRangeSummary(JSONObject vehicle) {
        if (vehicle == null) return "";
        String hero = heroDisplay(vehicle);
        String electricKm = rangeText(vehicle, "electricKm");
        int electricPct = validPercent(vehicle, "electricPercent");
        String fuelKm = rangeText(vehicle, "fuelKm");
        int fuelPct = validPercent(vehicle, "fuelPercent");
        String electricPart = !"—".equals(electricKm) ? "电 " + electricKm + " km"
                : electricPct >= 0 ? "电 " + electricPct + "%" : "";
        String fuelPart = !"—".equals(fuelKm) ? "油 " + fuelKm + " km"
                : fuelPct >= 0 ? "油 " + fuelPct + "%" : "";
        if ("— km".equals(hero) && electricPart.isEmpty() && fuelPart.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!"— km".equals(hero)) sb.append(hero);
        if (!electricPart.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(electricPart);
        }
        if (!fuelPart.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(fuelPart);
        }
        return sb.toString();
    }

    static boolean shouldInterceptBack(boolean hasConfirm, boolean settingsOpen, boolean twoPageShell, int currentPage) {
        return hasConfirm || settingsOpen || (twoPageShell && currentPage > 0);
    }
}
