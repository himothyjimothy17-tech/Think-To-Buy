package org.biobuzz.sim.hardware;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Telemetry that shows up in the browser's "Telemetry" panel instead of the
 * Driver Station.
 *
 * Behaves like the real one: addData() collects lines, update() sends them,
 * and (with auto-clear on, the default) the next addData() starts a fresh screen.
 */
public final class SimTelemetry implements Telemetry {

    private final List<Object> entries = new ArrayList<>(); // SimItem or SimLine
    private final SimLog log = new SimLog();
    private final List<Runnable> actions = new ArrayList<>();
    private boolean autoClear = true;
    private int msTransmissionInterval = 250;
    private String itemSeparator = " | ";
    private String captionValueSeparator = " : ";

    /** The last screen sent by update(). Read by the physics thread. */
    private volatile List<String> published = Collections.emptyList();
    /** Increases on every update() so the browser only redraws when something changed. */
    private volatile long version;

    public List<String> publishedLines() {
        return published;
    }

    public long version() {
        return version;
    }

    /** Clears everything (used when a new OpMode starts). */
    public void resetForNewOpMode() {
        entries.clear();
        log.clear();
        actions.clear();
        autoClear = true;
        published = Collections.emptyList();
        version++;
    }

    // ---- Telemetry ----

    @Override
    public Item addData(String caption, String format, Object... args) {
        return add(new SimItem(caption, () -> String.format(format, args)));
    }

    @Override
    public Item addData(String caption, Object value) {
        return add(new SimItem(caption, () -> String.valueOf(value)));
    }

    @Override
    public <T> Item addData(String caption, Func<T> valueProducer) {
        return add(new SimItem(caption, () -> String.valueOf(valueProducer.value())).retainAsFunc());
    }

    @Override
    public <T> Item addData(String caption, String format, Func<T> valueProducer) {
        return add(new SimItem(caption, () -> String.format(format, valueProducer.value())).retainAsFunc());
    }

    private Item add(SimItem item) {
        entries.add(item);
        return item;
    }

    @Override
    public boolean removeItem(Item item) {
        return entries.remove(item);
    }

    @Override
    public void clear() {
        entries.removeIf(e -> !(e instanceof SimItem) || !((SimItem) e).retained);
    }

    @Override
    public void clearAll() {
        entries.clear();
        log.clear();
    }

    @Override
    public Object addAction(Runnable action) {
        actions.add(action);
        return action;
    }

    @Override
    public boolean removeAction(Object token) {
        return actions.remove(token);
    }

    @Override
    public void speak(String text) {
    }

    @Override
    public void speak(String text, String languageCode, String countryCode) {
    }

    @Override
    public boolean update() {
        for (Runnable r : actions) {
            r.run();
        }
        List<String> lines = new ArrayList<>();
        for (Object e : entries) {
            if (e instanceof SimItem) {
                lines.add(((SimItem) e).render());
            } else {
                lines.add(((SimLine) e).render());
            }
        }
        lines.addAll(log.lines());
        published = Collections.unmodifiableList(lines);
        version++;
        if (autoClear) {
            clear();
        }
        return true;
    }

    @Override
    public Line addLine() {
        return addLine("");
    }

    @Override
    public Line addLine(String lineCaption) {
        SimLine line = new SimLine(lineCaption);
        entries.add(line);
        return line;
    }

    @Override
    public boolean removeLine(Line line) {
        return entries.remove(line);
    }

    @Override
    public boolean isAutoClear() {
        return autoClear;
    }

    @Override
    public void setAutoClear(boolean autoClear) {
        this.autoClear = autoClear;
    }

    @Override
    public int getMsTransmissionInterval() {
        return msTransmissionInterval;
    }

    @Override
    public void setMsTransmissionInterval(int msTransmissionInterval) {
        this.msTransmissionInterval = msTransmissionInterval;
    }

    @Override
    public String getItemSeparator() {
        return itemSeparator;
    }

    @Override
    public void setItemSeparator(String itemSeparator) {
        this.itemSeparator = itemSeparator;
    }

    @Override
    public String getCaptionValueSeparator() {
        return captionValueSeparator;
    }

    @Override
    public void setCaptionValueSeparator(String captionValueSeparator) {
        this.captionValueSeparator = captionValueSeparator;
    }

    @Override
    public void setDisplayFormat(DisplayFormat displayFormat) {
    }

    @Override
    public Log log() {
        return log;
    }

    // ---- Items, lines and the log ----

    private interface ValueText {
        String get();
    }

    private final class SimItem implements Item {
        private String caption;
        private ValueText value;
        private boolean retained;
        private final List<SimItem> extras = new ArrayList<>();

        SimItem(String caption, ValueText value) {
            this.caption = caption;
            this.value = value;
        }

        SimItem retainAsFunc() {
            // Items built from a Func are re-evaluated every update, like the SDK.
            return this;
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(caption).append(captionValueSeparator).append(safe(value));
            for (SimItem extra : extras) {
                sb.append(itemSeparator).append(extra.caption).append(captionValueSeparator).append(safe(extra.value));
            }
            return sb.toString();
        }

        @Override
        public String getCaption() {
            return caption;
        }

        @Override
        public Item setCaption(String caption) {
            this.caption = caption;
            return this;
        }

        @Override
        public Item setValue(String format, Object... args) {
            value = () -> String.format(format, args);
            return this;
        }

        @Override
        public Item setValue(Object v) {
            value = () -> String.valueOf(v);
            return this;
        }

        @Override
        public <T> Item setValue(Func<T> valueProducer) {
            value = () -> String.valueOf(valueProducer.value());
            return this;
        }

        @Override
        public <T> Item setValue(String format, Func<T> valueProducer) {
            value = () -> String.format(format, valueProducer.value());
            return this;
        }

        @Override
        public Item setRetained(Boolean retained) {
            this.retained = retained != null && retained;
            return this;
        }

        @Override
        public boolean isRetained() {
            return retained;
        }

        @Override
        public Item addData(String caption, String format, Object... args) {
            extras.add(new SimItem(caption, () -> String.format(format, args)));
            return this;
        }

        @Override
        public Item addData(String caption, Object v) {
            extras.add(new SimItem(caption, () -> String.valueOf(v)));
            return this;
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            extras.add(new SimItem(caption, () -> String.valueOf(valueProducer.value())));
            return this;
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            extras.add(new SimItem(caption, () -> String.format(format, valueProducer.value())));
            return this;
        }
    }

    private final class SimLine implements Line {
        private final String caption;
        private final List<SimItem> items = new ArrayList<>();

        SimLine(String caption) {
            this.caption = caption;
        }

        String render() {
            StringBuilder sb = new StringBuilder(caption);
            for (SimItem item : items) {
                if (sb.length() > 0) {
                    sb.append(itemSeparator);
                }
                sb.append(item.caption).append(captionValueSeparator).append(safe(item.value));
            }
            return sb.toString();
        }

        @Override
        public Item addData(String caption, String format, Object... args) {
            SimItem i = new SimItem(caption, () -> String.format(format, args));
            items.add(i);
            return i;
        }

        @Override
        public Item addData(String caption, Object value) {
            SimItem i = new SimItem(caption, () -> String.valueOf(value));
            items.add(i);
            return i;
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            SimItem i = new SimItem(caption, () -> String.valueOf(valueProducer.value()));
            items.add(i);
            return i;
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            SimItem i = new SimItem(caption, () -> String.format(format, valueProducer.value()));
            items.add(i);
            return i;
        }
    }

    private static final class SimLog implements Log {
        private final List<String> entries = new ArrayList<>();
        private int capacity = 9;
        private DisplayOrder order = DisplayOrder.OLDEST_FIRST;

        List<String> lines() {
            List<String> out = new ArrayList<>(entries);
            if (order == DisplayOrder.NEWEST_FIRST) {
                Collections.reverse(out);
            }
            return out;
        }

        @Override
        public int getCapacity() {
            return capacity;
        }

        @Override
        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public DisplayOrder getDisplayOrder() {
            return order;
        }

        @Override
        public void setDisplayOrder(DisplayOrder displayOrder) {
            this.order = displayOrder;
        }

        @Override
        public void add(String entry) {
            entries.add(entry);
            while (entries.size() > capacity) {
                entries.remove(0);
            }
        }

        @Override
        public void add(String format, Object... args) {
            add(String.format(format, args));
        }

        @Override
        public void clear() {
            entries.clear();
        }
    }

    /**
     * Formats a value. A bad format string (e.g. "%d" with a double) throws
     * here, during update() - just like on the real robot, where it crashes
     * the OpMode. We want to find that bug in the sim, not at a competition.
     */
    private static String safe(ValueText v) {
        return v.get();
    }
}
