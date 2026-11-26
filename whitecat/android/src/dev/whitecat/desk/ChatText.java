package dev.whitecat.desk;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display inline chat emphasis while retaining the original text for copy and reconciliation. */
final class ChatText {
    private static final Pattern INLINE = Pattern.compile(
            "(?<!\\*)\\*\\*([^*\\r\\n]+)\\*\\*(?!\\*)|(?<!`)`([^`\\r\\n]+)`(?!`)");
    private static final int FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    private static final class Delimiter extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return 0;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {}
    }

    static CharSequence inline(String raw, int codeBackground) {
        Matcher matches = INLINE.matcher(raw);
        if (!matches.find()) return raw;
        SpannableString result = new SpannableString(raw);
        do {
            boolean bold = matches.start(1) >= 0;
            int group = bold ? 1 : 2;
            int start = matches.start(group), end = matches.end(group);
            result.setSpan(new Delimiter(), matches.start(), start, FLAGS);
            result.setSpan(new Delimiter(), end, matches.end(), FLAGS);
            result.setSpan(bold ? new StyleSpan(Typeface.BOLD) : new TypefaceSpan("monospace"), start, end, FLAGS);
            if (!bold) result.setSpan(new BackgroundColorSpan(codeBackground), start, end, FLAGS);
        } while (matches.find());
        return result;
    }
}
