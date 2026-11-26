package dev.whitecat.desk;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class AppearancePage {
    private final ChatActivity a;

    AppearancePage(ChatActivity activity) {
        a = activity;
    }

    void show() {
        a.page = "appearance";
        a.generation++;
        a.frame();

        LinearLayout top = a.row();
        a.pad(top, 10, 6);
        top.setMinimumHeight(a.dp(58));
        a.root.addView(top, a.lp(-1, -2));
        top.addView(a.icon(R.drawable.ic_back, R.id.back, "返回", a::backFromAppearance), a.lp(44, 44));
        TextView title = a.text("字体与背景", 20, a.ink);
        a.bold(title);
        top.addView(title, a.weight());
        a.divider(a.root);

        ScrollView scroll = new ScrollView(a);
        a.root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = a.col();
        a.pad(body, 20, 24);
        scroll.addView(body);

        LinearLayout preview = a.col();
        preview.setBackground(a.bg(a.surface, 16, false));
        a.pad(preview, 16, 14);
        body.addView(preview, a.lp(-1, -2));
        TextView previewTitle = a.text("阅读预览", 17, a.ink);
        a.bold(previewTitle);
        preview.addView(previewTitle);
        TextView sample = a.text("白猫已连接工作站。可以继续上次的对话。", 15, a.secondary);
        sample.setTag("appearance_preview");
        sample.setPadding(0, a.dp(8), 0, 0);
        preview.addView(sample);

        Appearance.Background current = Appearance.background(a);
        int percent = Appearance.fontPercent(a);
        LinearLayout fonts = choices(body, "字体大小");
        String[] labels = {"紧凑", "标准", "大字", "特大"};
        for (int i = 0; i < labels.length; i++) {
            final int value = Appearance.FONT_PERCENTAGES[i];
            boolean selected = value == percent;
            Button option = choice(fonts, labels[i], "appearance_font_" + value, selected,
                    selected ? a.tile : a.surface, a.ink, 54, () -> Appearance.select(a, value, current));
            option.setContentDescription(labels[i] + "，系统字号的 " + value + "%");
        }

        LinearLayout backgrounds = choices(body, "页面背景");
        for (Appearance.Background bg : Appearance.Background.values()) {
            choice(backgrounds, bg.label, "appearance_bg_" + bg.key, bg == current,
                    a.getColor(bg.swatch), a.getColor(bg.swatchText), 78,
                    () -> Appearance.select(a, percent, bg));
        }

        Button reset = a.button("恢复默认", View.generateViewId(),
                () -> Appearance.select(a, 100, Appearance.Background.OBSIDIAN));
        reset.setTag("appearance_reset");
        reset.setMinimumHeight(a.dp(50));
        reset.setBackground(a.bg(a.surface, 14, false));
        LinearLayout.LayoutParams box = a.lp(-1, -2);
        box.topMargin = a.dp(34);
        body.addView(reset, box);
    }

    private LinearLayout choices(LinearLayout body, String heading) {
        TextView title = a.text(heading, 17, a.ink);
        a.bold(title);
        title.setPadding(a.dp(2), a.dp(28), 0, a.dp(12));
        body.addView(title);
        LinearLayout options = a.row();
        options.setBaselineAligned(false);
        body.addView(options, a.lp(-1, -2));
        return options;
    }

    private Button choice(LinearLayout row, String label, String tag, boolean selected,
                          int fill, int ink, int minHeight, Runnable action) {
        Button option = a.button(label, View.generateViewId(), action);
        option.setTag(tag);
        option.setSelected(selected);
        option.setTextSize(15);
        option.setTextColor(ink);
        option.setGravity(Gravity.CENTER);
        option.setMinimumHeight(a.dp(minHeight));
        option.setStateListAnimator(null);
        a.pad(option, 4, 6);
        GradientDrawable shape = a.bg(fill, 12, false);
        shape.setStroke(a.dp(selected ? 2 : 1), selected ? Appearance.color(a, android.R.attr.colorAccent) : a.edge);
        option.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0x00ffffff) | 0x24000000), shape, null));
        if (selected) a.bold(option);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(0, -2, 1f);
        if (row.getChildCount() > 0) box.setMarginStart(a.dp(8));
        row.addView(option, box);
        return option;
    }
}
