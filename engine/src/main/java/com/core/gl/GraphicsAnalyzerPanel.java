package com.core.gl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.HorizontalScrollView;
import com.core.engine.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Mobile single-row strip. Long chains scroll horizontally without covering another row. */
final class GraphicsAnalyzerPanel extends HorizontalScrollView {
    private final Strip strip;
    private final int availableWidth;
    GraphicsAnalyzerPanel(Context context, int availableWidth) {
        super(context);
        this.availableWidth = availableWidth;
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setFillViewport(true);
        strip = new Strip(context);
        addView(strip);
    }
    void update(ArtemisGraphicsStats.Snapshot snapshot, String status) { strip.update(snapshot, status); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(availableWidth, strip.contentWidth());
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
    }

    private final class Strip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int presentationColor;
        private final float density;
        private final List<ArtemisGraphicsStats.Node> rows = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<Float> labelWidths = new ArrayList<>();
        private String summary = "—", presentation = "", output = " → —";
        private float summaryWidth, labelsWidth, presentationWidth, outputWidth;
        private boolean complete;
        private double total;
        Strip(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            presentationColor = context.getColor(R.color.graphics_analyzer_presentation);
            paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            setBackgroundColor(Color.argb(218, 14, 22, 24));
        }
        private float dp(float value) { return value * density; }
        void update(ArtemisGraphicsStats.Snapshot snapshot, String status) {
            rows.clear(); labels.clear(); labelWidths.clear();
            for (ArtemisGraphicsStats.Node node : snapshot.nodes) if (node.id != null) rows.add(node);
            complete = !rows.isEmpty(); total = 0;
            labelsWidth = 0;
            StringBuilder accessible = new StringBuilder();
            for (ArtemisGraphicsStats.Node row : rows) {
                double ms = row.gpuMs;
                complete &= Double.isFinite(ms);
                if (Double.isFinite(ms)) total += ms;
                String scale = scale(row);
                String label = model(row.id) + scale + " " + number(ms);
                float width = paint.measureText(label) + dp(12);
                labels.add(label); labelWidths.add(width); labelsWidth += width;
                accessible.append(row.id).append(scale).append(' ').append(number(ms)).append(" ms; ");
            }
            String inputSize = dimensions(snapshot.sourceWidth, snapshot.sourceHeight);
            String processedSize = dimensions(snapshot.outputWidth, snapshot.outputHeight);
            String displaySize = dimensions(snapshot.viewportWidth, snapshot.viewportHeight);
            presentation = ""; presentationWidth = 0;
            output = " → " + displaySize;
            if (snapshot.outputWidth > 0 && snapshot.outputHeight > 0 && snapshot.viewportWidth > 0 && snapshot.viewportHeight > 0 &&
                    (snapshot.outputWidth != snapshot.viewportWidth || snapshot.outputHeight != snapshot.viewportHeight)) {
                double a = (double)snapshot.viewportWidth / snapshot.outputWidth;
                double b = (double)snapshot.viewportHeight / snapshot.outputHeight;
                // Presentation has its own card, but no invented GPU time or share of the measured stripe.
                presentation = getResources().getString(R.string.graphics_analyzer_presentation) +
                        scale(Math.min(a, b), Math.max(a, b), Math.sqrt(a * b));
                presentationWidth = paint.measureText(presentation) + dp(12);
                output = " → " + displaySize;
            }
            outputWidth = paint.measureText(output) + dp(12);
            String fps = snapshot.sourceWidth > 0 ? String.format(Locale.getDefault(), "%.0f", snapshot.fps) : "—";
            String metrics = fps + " FPS · GPU " + number(complete ? snapshot.gpuMs : Double.NaN) + "ms"
                    + (status == null ? "" : " !") + " · " + inputSize + " →";
            String device = device(snapshot.renderer);
            // Prefer the complete resolution flow and filter labels over the device name.
            summary = !device.isEmpty() && paint.measureText(device + " · " + metrics) + dp(12)
                    + labelsWidth * 1.2f + presentationWidth + outputWidth <= availableWidth ? device + " · " + metrics : metrics;
            summaryWidth = paint.measureText(summary) + dp(12);
            String flow = getResources().getString(R.string.graphics_analyzer_resolution_flow, inputSize, processedSize, displaySize);
            setContentDescription(snapshot.renderer + "; " + metrics + "; " + accessible + presentation + "; " + flow + "; " +
                    (status == null ? "" : status));
            requestLayout(); invalidate();
        }
        int contentWidth() {
            float minimum = summaryWidth + labelsWidth + presentationWidth + outputWidth;
            float preferred = minimum + labelsWidth * .2f;
            return Math.max(1, (int)Math.ceil(Math.max(minimum, Math.min(availableWidth, preferred))));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = contentWidth();
            int height = Math.max(Math.round(dp(26)), (int)Math.ceil(paint.getFontSpacing() + dp(8)));
            setMeasuredDimension(width, height);
        }
        @Override protected void onDraw(Canvas canvas) {
            Paint.FontMetrics font = paint.getFontMetrics();
            float baseline = (getHeight() - dp(3) - font.ascent - font.descent) / 2f;
            paint.setColor(Color.WHITE);
            canvas.drawText(summary, dp(6), baseline, paint);
            float start = summaryWidth, barWidth = Math.max(0, getWidth() - start - presentationWidth - outputWidth);
            float barEnd = start + barWidth;
            float extra = Math.max(0, barWidth - labelsWidth);
            float x = start;
            // Label cells reserve readable text space. The bottom stripe retains exact time proportions.
            for (int i = 0; i < rows.size(); i++) {
                double ms = rows.get(i).gpuMs;
                double weight = complete && total > 0 ? ms / total : 1.0 / rows.size();
                float width = labelWidths.get(i) + extra * (float)weight;
                int color = nodeColor(rows.get(i).id);
                paint.setColor(Color.argb(185, Color.red(color), Color.green(color), Color.blue(color)));
                canvas.drawRect(x, 0, x + width, getHeight(), paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(labels.get(i), x + (width - paint.measureText(labels.get(i))) / 2, baseline, paint);
                x += width;
            }
            if (complete && total > 0) {
                x = start;
                for (int i = 0; i < rows.size(); i++) {
                    double ms = rows.get(i).gpuMs;
                    float next = i == rows.size() - 1 ? barEnd : x + barWidth * (float)(ms / total);
                    paint.setColor(nodeColor(rows.get(i).id));
                    canvas.drawRect(x, getHeight() - dp(3), next, getHeight(), paint); x = next;
                }
            }
            x = barEnd;
            paint.setColor(Color.WHITE);
            if (!presentation.isEmpty()) {
                paint.setColor(presentationColor);
                canvas.drawRect(x, 0, x + presentationWidth, getHeight(), paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(presentation, x + dp(6), baseline, paint); x += presentationWidth;
            }
            canvas.drawText(output, x + dp(6), baseline, paint);
        }
        private String dimensions(int width, int height) {
            return width > 0 && height > 0 ? width + "×" + height : "—";
        }
        private String number(double value) {
            return Double.isFinite(value) ? String.format(Locale.getDefault(), "%.1f", value) : "—";
        }
        private String factor(double value) {
            return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        private String scale(ArtemisGraphicsStats.Node node) {
            return scale(node.scaleMin, node.scaleMax, node.scale);
        }
        private String scale(double minimum, double maximum, double value) {
            if (!Double.isFinite(value) || value <= 0) return " (—)";
            if (minimum == 1.0 && maximum == 1.0) return "";
            String ratio = Math.abs(maximum - minimum) > .002 * maximum
                    ? factor(minimum) + "–" + factor(maximum) : factor(value);
            return " (" + ratio + "X)";
        }
        private String device(String renderer) {
            if (renderer == null) return "";
            return renderer.replace("(TM)", "").replace("(R)", "").replaceAll("\\s+", " ").trim();
        }
        private int nodeColor(String id) {
            GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
            return definition == null ? presentationColor : definition.color;
        }
        private String model(String id) {
            GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
            return definition == null ? id : definition.shortName;
        }
    }
}
