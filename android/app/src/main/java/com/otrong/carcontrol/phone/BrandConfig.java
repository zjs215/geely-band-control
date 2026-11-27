package com.otrong.carcontrol.phone;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Resource and host identities for the supported official widgets. */
final class BrandConfig {
    enum Brand { GALAXY, LYNKCO, GEELY, ZEEKR }
    enum SlotMode { DYNAMIC, CONFIGURABLE }
    static final BrandConfig GALAXY = new BrandConfig(Brand.GALAXY);
    static final BrandConfig LYNKCO = new BrandConfig(Brand.LYNKCO);
    static final BrandConfig GEELY = new BrandConfig(Brand.GEELY);
    static final BrandConfig ZEEKR = new BrandConfig(Brand.ZEEKR);
    static final List<BrandConfig> OPTIONS = List.of(GALAXY, LYNKCO, GEELY, ZEEKR);
    final Brand brand;
    final String name;
    final String appName;
    final SlotMode slotMode;
    final int heightDp, hostId;
    final String cardsKey;
    record Slot(String container, String icon, String progress) {}
    final List<Slot> slotModels;
    final List<String> slots, energyBars, loading;
    final String packageName;
    final String provider;
    final String normalLayout;
    final String setupLayout;
    final String unavailableLayout;
    final String settings;
    final String refresh;
    final List<String> refreshViews;
    final String confirm;
    final String accept;
    final String cancel;
    final String confirmTitle;
    final String mileage;
    final String mileageValue;
    final String mileageUnit;
    final String electric;
    final String electricBar;
    final String oil;
    final String oilBar;
    final String alarm;
    final String motion;
    final String time;
    final String energyValue;
    final String energyUnit;
    final String slotLoading;
    final String slotIcon;
    final String iconPrefix;
    final String namespace;
    private BrandConfig(Brand brand) {
        this.brand = brand;
        if (brand == Brand.ZEEKR) {
            name = "极氪";
            appName = name;
            slotMode = SlotMode.DYNAMIC;
            heightDp = 175;
            hostId = 0x5a4b;
            cardsKey = "zeekrWidgetCards";
            slotModels = List.of(new Slot("ll_car_control_1", "iv_car_control_1_icon", "iv_car_control_loading_1"),
                    new Slot("ll_car_control_2", "iv_car_control_2_icon", "iv_car_control_loading_2"),
                    new Slot("ll_car_control_3", "iv_car_control_3_icon", "iv_car_control_loading_3"),
                    new Slot("ll_car_control_4", "iv_car_control_4_icon", "iv_car_control_loading_4"));
            slots = Collections.unmodifiableList(slotModels.stream().map(Slot::container).collect(Collectors.toList()));
            energyBars = List.of();
            loading = List.of("pb_refreshing");
            packageName = "com.zeekrlife.mobile";
            provider = "com.zeekrlife.vehicle.smallwidget.widgetprovider.ZeekrWidget";
            normalLayout = "vehicle_widget_layout";
            setupLayout = null; // Guest and owner states share the normal layout.
            unavailableLayout = null;
            settings = "ll_widget_setting";
            refresh = "ll_refresh";
            refreshViews = List.of(refresh, "iv_refresh", "tv_update_time");
            confirm = null;
            accept = null;
            cancel = null;
            confirmTitle = null;
            mileage = "rl_car_owner";
            mileageValue = "tv_cruising_range";
            mileageUnit = "tv_cruising_range_unit";
            electric = "tv_charge_endurance";
            electricBar = null;
            oil = "tv_oil_endurance";
            oilBar = null;
            alarm = "ll_charge";
            motion = "iv_widget_charge_text";
            time = "tv_update_time";
            energyValue = null;
            energyUnit = null;
            slotLoading = null;
            slotIcon = null;
            iconPrefix = "widget_car_control_";
            namespace = "ZEEKR_V1";
            return;
        }
        boolean lynk = brand == Brand.LYNKCO;
        boolean geely = brand == Brand.GEELY;
        name = lynk ? "领克" : geely ? "吉利汽车" : "银河";
        appName = lynk ? "领克" : geely ? "吉利汽车" : "吉利银河";
        slotMode = lynk ? SlotMode.CONFIGURABLE : SlotMode.DYNAMIC;
        heightDp = lynk ? 155 : 175;
        hostId = lynk ? 0x4c43 : geely ? 0x4741 : 0x4742;
        cardsKey = lynk ? "lynkcoWidgetCards" : geely ? "geelyWidgetCards" : "widgetCards";
        slotModels = lynk ? List.of(new Slot("fr_unlock", "unlock", "pb_unlock"),
                new Slot("fr_window", "window", "pb_window"), new Slot("fr_kjck", "kjck", "pb_kjck"),
                new Slot("fr_ccqk", "ccqk_on", "pb_ccqk_on"))
                : List.of(new Slot("wgFun1Container", "wg_view_fun_status", "wg_view_fun_loading"),
                new Slot("wgFun2Container", "wg_view_fun_status", "wg_view_fun_loading"),
                new Slot("wgFun3Container", "wg_view_fun_status", "wg_view_fun_loading"),
                new Slot("wgFun4Container", "wg_view_fun_status", "wg_view_fun_loading"));
        slots = Collections.unmodifiableList(slotModels.stream().map(Slot::container).collect(Collectors.toList()));
        energyBars = lynk ? List.of("progress_bar_electricity", "progress_bar_h") : List.of("wg_pb_energy_normal", "wg_pb_energy_warn", "wg_pb_energy_alert");
        loading = lynk ? List.of("fr_sx", "ll_loading_auth") : List.of("wg_view_update_time_loading", "wg_tv_update_time_loading");
        packageName = lynk ? "com.lynkco.customer" : geely ? "com.geely.consumer" : "com.geelygalaxy.customer";
        provider = switch (brand) {
            case LYNKCO -> "com.lynkco.vehicle.widget.ListWidgetProvider";
            case GEELY -> "com.geely.consumer.carcontrol.widget.appwidget.Wg_Middle";
            case GALAXY -> "com.geely.galaxy.car.weight.appwidget.WgProviderMiddle";
            case ZEEKR -> throw new AssertionError(brand);
        };
        normalLayout = lynk ? "layout_widget" : "wg_middle_normal";
        setupLayout = lynk ? null : geely ? "wg_middle_unbound" : "wg_middle_init";
        unavailableLayout = geely ? "wg_middle_unable" : null;
        settings = lynk ? "ic_bj_a" : "wg_btn_setting";
        refresh = lynk ? "ic_widget_sx" : geely ? "wg_view_ll_update_time" : "wg_btn_update_time";
        refreshViews = geely ? List.of(refresh, "wg_iv_update_time", "wg_iv_update_time_shadow", "wg_tv_update_time")
                : List.of(refresh);
        confirm = lynk ? "verify" : "wg_middle_layout_confirm";
        accept = lynk ? "tv_verify" : "wg_middle_confirm_btn_ok";
        cancel = lynk ? "tv_cancel" : "wg_middle_confirm_btn_cancel";
        confirmTitle = lynk ? "ty_confirm_title" : "wg_middle_confirm_title";
        mileage = lynk ? "mileage" : geely ? "wg_middle_layout_mileage" : "wg_view_mileage";
        mileageValue = lynk ? "tv_mileage" : geely ? "wg_middle_text_mileage" : "wg_tv_mileage";
        mileageUnit = lynk ? "txt_km" : geely ? "wg_middle_text_mileage_km" : "wg_tv_mileage_unit";
        electric = lynk ? "ll_electricity" : "wg_energy_container_electric";
        electricBar = lynk ? "progress_bar_electricity" : "wg_energy_bar_electric";
        oil = lynk ? "ly_oil" : "wg_energy_container_oil";
        oilBar = lynk ? "progress_bar_h" : "wg_energy_bar_oil";
        alarm = lynk ? "condition_ll" : "wg_alarm_container";
        motion = lynk ? "condition_txt" : "wg_tv_warn_text";
        time = lynk ? "time" : "wg_tv_update_time";
        energyValue = lynk ? null : "wg_tv_energy_percent";
        energyUnit = lynk ? null : "wg_tv_energy_percent_unit";
        slotLoading = lynk ? null : "wg_view_fun_loading";
        slotIcon = lynk ? null : "wg_view_fun_status";
        iconPrefix = lynk ? null : geely ? "wg_" : "car_wg_funicon_";
        namespace = lynk ? "LYNKCO_V1" : geely ? "GEELY_V1" : "GARAGE_V1";
    }
    boolean recognizedIconsOnly() { return this == GEELY || this == ZEEKR; }

    Slot slot(String container) { return slotModels.stream().filter(slot -> slot.container().equals(container)).findFirst().orElse(null); }
    static BrandConfig fromId(String id) {
        return OPTIONS.stream().filter(config -> config.brand.name().equals(id)).findFirst().orElse(GALAXY);
    }
}
