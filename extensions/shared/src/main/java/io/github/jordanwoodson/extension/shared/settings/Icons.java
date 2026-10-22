package io.github.jordanwoodson.extension.shared.settings;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings screen's icons, drawn from SVG path data: the extension ships code only, no resources. The
 * 24-unit icons are from Google's Material Icons (Apache License 2.0); the Lurk logo is assets/icon.png
 * traced by bin/app-icon.
 */
final class Icons {
    static final String BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
    static final String SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 "
            + "3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 "
            + "5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";
    static final String CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 "
            + "19 17.59 13.41 12z";
    static final String SHIELD = "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z";
    static final String DASHBOARD = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
    static final String VIDEO = "M21 3H3c-1.11 0-2 .89-2 2v12c0 1.1.89 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 "
            + "5c0-1.11-.9-2-2-2zm0 14H3V5h18v12zm-5-6l-7 4V7z";
    static final String CHAT = "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9z"
            + "m8 5H6v-2h8v2zm4-6H6V6h12v2z";
    static final String STAR = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z";
    static final String TUNE = "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 "
            + "4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";
    static final String PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z";
    static final String RESTART = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 "
            + "6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 "
            + "11h7V4l-2.35 2.35z";
    static final String EXPORT = "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z";
    static final String IMPORT = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z";
    static final String BUG = "M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 "
            + "12 5c-.49 0-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 "
            + "1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66"
            + ".09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z";
    static final String RESET = "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 "
            + "3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z";
    static final String WARNING = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";

    /** The hooded figure of assets/icon.png in its 1254-unit square. */
    static final String LOGO = "M419,1019.9C349.9,1014.2 295.6,965.9 285.4,901C284.1,892.7 284,862.8 284.2,673L284.5,"
            + "454.5L287.2,444C296.4,407 316.7,376.2 348.1,351.6C354.5,346.6 371.6,332.3 386.1,319.8C471,246.8 524.3,"
            + "209.2 573,187.7C595.4,177.9 606,175.5 625.6,176.2C641.4,176.8 648.2,178.9 663.2,188C697,208.6 727,233 "
            + "759.5,266.1C814.1,321.8 846.6,371.6 910.9,497.9C957,588.6 977.6,632.9 984.6,656.8C987.7,667.3 988.7,"
            + "681.9 987.1,691.2C983.7,710.7 964.2,741.5 923.4,791.6C908.4,810 875.5,848.6 873.9,849.5C873.4,849.9 "
            + "869.7,849.2 865.7,848C861.7,846.8 849.1,843.4 837.7,840.5C826.3,837.6 816.8,835 816.7,834.8C816.6,834.6 "
            + "832.5,818.8 852,799.8C901.3,751.9 920,731.7 931.7,713.9C947.2,690.2 950.7,669.7 943.1,647.1C932.2,614.7 "
            + "887.8,567.8 820.9,517.8C804.4,505.5 765.7,479 752.7,471.1C750.6,469.8 749.2,468.4 749.7,468C750.7,467 "
            + "780.7,457.4 796.8,452.9C803.5,451 809,449.3 809,449C809,448.1 792.2,442.1 783.9,440.1C766.7,435.8 742.6,"
            + "434.7 725.5,437.5C688.2,443.5 637.8,468.4 577.5,510.5C525.9,546.5 473.2,592.8 460.6,613.2C454.7,622.7 "
            + "449.5,637.2 447.5,649.3C445.2,663.4 445.3,813.8 447.6,828.4C450.9,849 458.8,869.8 469.8,886.5C478.3,"
            + "899.3 498.9,918.8 510.4,924.7C513.4,926.2 513.4,926.2 512,924.2C490.8,895 481.4,871.7 477,838C475.9,"
            + "829.7 474.3,768 475.1,768C475.4,768 477.7,770.9 480.4,774.4C495.5,794.4 524,809.2 570.5,821C590.9,826.2 "
            + "627.3,833.3 684,843C746.6,853.7 781.3,860.1 804.5,865C879.1,880.9 914.3,895 943.3,920.4C966.9,941.2 "
            + "988.5,973.2 1000.3,1005C1005.8,1019.9 1005.8,1019.6 1001.3,1020.3C995.7,1021.3 430.2,1020.9 419,1019.9Z"
            + "M581.5,695.6C566.7,692.2 557.4,686.9 545.8,675.4C533.4,663.1 523,645.2 515.8,623.8C513.6,617.2 512,611.7 "
            + "512.2,611.5C512.8,610.9 538.1,616.9 549,620.1C583.6,630.6 611.1,646.8 629.6,667.6C636.1,675 642,682.8 "
            + "642,684.1C642,685.3 622.3,692.6 614.4,694.4C603.9,696.7 588.7,697.3 581.5,695.6ZM751,696.4C738.3,694.9 "
            + "712,686.2 712,683.6C712.1,681.6 725.8,665.4 733.2,658.5C753.2,640 782.1,625.6 817.5,616.5C828.5,613.7 "
            + "842,611 842,611.6C842,611.8 840.6,616.4 838.9,621.7C835.3,633.1 827,650.7 820.8,660C810.3,675.9 794.4,"
            + "688.9 780,693.5C772.2,695.9 758.5,697.3 751,696.4Z";
    static final float LOGO_SIZE = 1254f;

    private static final Pattern TOKEN =
            Pattern.compile("([MmLlHhVvCcSsQqZz])|([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)");

    private Icons() {
    }

    /** A 24-unit Material icon, {@code size} pixels square, in {@code color}. */
    static Drawable icon(String data, int color, int size) {
        return new PathDrawable(parse(data), 24f, color, size, 0, 0);
    }

    /** The Lurk logo: the mint figure on a navy rounded square, {@code size} pixels. */
    static Drawable logo(int size) {
        Path figure = parse(LOGO);
        figure.setFillType(Path.FillType.EVEN_ODD);
        return new PathDrawable(figure, LOGO_SIZE, Palette.MINT, size, Palette.NAVY, LOGO_SIZE * 0.22f);
    }

    /**
     * SVG path data as an android Path: the commands M, L, H, V, C, S, Q and Z in both absolute and relative
     * form, with repeated arguments. Arcs aren't needed by these icons.
     */
    static Path parse(String data) {
        List<Object> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(data);
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? (Object) matcher.group(1).charAt(0)
                    : (Object) Float.parseFloat(matcher.group(2)));
        }
        Path path = new Path();
        float x = 0, y = 0, startX = 0, startY = 0, controlX = 0, controlY = 0;
        boolean cubic = false;
        char command = 'M';
        int i = 0;
        while (i < tokens.size()) {
            if (tokens.get(i) instanceof Character) {
                command = (Character) tokens.get(i++);
                if (command == 'Z' || command == 'z') {
                    path.close();
                    x = startX;
                    y = startY;
                    cubic = false;
                    continue;
                }
            }
            boolean relative = Character.isLowerCase(command);
            float baseX = relative ? x : 0, baseY = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M':
                    x = baseX + number(tokens, i++);
                    y = baseY + number(tokens, i++);
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    // Further pairs after a move are lines.
                    command = relative ? 'l' : 'L';
                    cubic = false;
                    break;
                case 'L':
                    x = baseX + number(tokens, i++);
                    y = baseY + number(tokens, i++);
                    path.lineTo(x, y);
                    cubic = false;
                    break;
                case 'H':
                    x = baseX + number(tokens, i++);
                    path.lineTo(x, y);
                    cubic = false;
                    break;
                case 'V':
                    y = baseY + number(tokens, i++);
                    path.lineTo(x, y);
                    cubic = false;
                    break;
                case 'C': {
                    float x1 = baseX + number(tokens, i++), y1 = baseY + number(tokens, i++);
                    controlX = baseX + number(tokens, i++);
                    controlY = baseY + number(tokens, i++);
                    x = baseX + number(tokens, i++);
                    y = baseY + number(tokens, i++);
                    path.cubicTo(x1, y1, controlX, controlY, x, y);
                    cubic = true;
                    break;
                }
                case 'S': {
                    // The first control point mirrors the previous curve's second one.
                    float x1 = cubic ? 2 * x - controlX : x, y1 = cubic ? 2 * y - controlY : y;
                    controlX = baseX + number(tokens, i++);
                    controlY = baseY + number(tokens, i++);
                    x = baseX + number(tokens, i++);
                    y = baseY + number(tokens, i++);
                    path.cubicTo(x1, y1, controlX, controlY, x, y);
                    cubic = true;
                    break;
                }
                case 'Q': {
                    float x1 = baseX + number(tokens, i++), y1 = baseY + number(tokens, i++);
                    x = baseX + number(tokens, i++);
                    y = baseY + number(tokens, i++);
                    path.quadTo(x1, y1, x, y);
                    cubic = false;
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported path command " + command);
            }
        }
        return path;
    }

    private static float number(List<Object> tokens, int index) {
        Object token = index < tokens.size() ? tokens.get(index) : null;
        if (!(token instanceof Float)) throw new IllegalArgumentException("Path data: expected a number at " + index);
        return (Float) token;
    }

    /** A filled path scaled to the bounds, optionally on a rounded square. Tinting recolors the path. */
    static final class PathDrawable extends Drawable {
        private final Path path;
        private final float viewport;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint background;
        private final float corner;
        private final Path scaled = new Path();
        private final Matrix matrix = new Matrix();
        private final RectF bounds = new RectF();

        PathDrawable(Path path, float viewport, int color, int size, int backgroundColor, float corner) {
            this.path = path;
            this.viewport = viewport;
            this.size = size;
            this.corner = corner;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            if (backgroundColor != 0) {
                background = new Paint(Paint.ANTI_ALIAS_FLAG);
                background.setColor(backgroundColor);
            } else {
                background = null;
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect area = getBounds();
            float scaleX = area.width() / viewport, scaleY = area.height() / viewport;
            if (background != null) {
                bounds.set(area);
                canvas.drawRoundRect(bounds, corner * scaleX, corner * scaleY, background);
            }
            matrix.setScale(scaleX, scaleY);
            matrix.postTranslate(area.left, area.top);
            path.transform(matrix, scaled);
            canvas.drawPath(scaled, paint);
        }

        @Override
        public void setTintList(ColorStateList tint) {
            if (tint == null) return;
            paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            if (background != null) background.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
