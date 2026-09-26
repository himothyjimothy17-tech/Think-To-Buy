package org.firstinspires.ftc.robotcore.external;

/**
 * Sends text to the Driver Station screen. In the simulator it shows up in
 * the "Telemetry" panel of the browser.
 *
 * Remember: nothing appears until you call update().
 */
public interface Telemetry {

    Item addData(String caption, String format, Object... args);

    Item addData(String caption, Object value);

    <T> Item addData(String caption, Func<T> valueProducer);

    <T> Item addData(String caption, String format, Func<T> valueProducer);

    boolean removeItem(Item item);

    void clear();

    void clearAll();

    Object addAction(Runnable action);

    boolean removeAction(Object token);

    void speak(String text);

    void speak(String text, String languageCode, String countryCode);

    boolean update();

    Line addLine();

    Line addLine(String lineCaption);

    boolean removeLine(Line line);

    boolean isAutoClear();

    void setAutoClear(boolean autoClear);

    int getMsTransmissionInterval();

    void setMsTransmissionInterval(int msTransmissionInterval);

    String getItemSeparator();

    void setItemSeparator(String itemSeparator);

    String getCaptionValueSeparator();

    void setCaptionValueSeparator(String captionValueSeparator);

    void setDisplayFormat(DisplayFormat displayFormat);

    default void setNumDecimalPlaces(int minDecimalPlaces, int maxDecimalPlaces) {
    }

    Log log();

    enum DisplayFormat { CLASSIC, MONOSPACE, HTML }

    interface Item {
        String getCaption();

        Item setCaption(String caption);

        Item setValue(String format, Object... args);

        Item setValue(Object value);

        <T> Item setValue(Func<T> valueProducer);

        <T> Item setValue(String format, Func<T> valueProducer);

        Item setRetained(Boolean retained);

        boolean isRetained();

        Item addData(String caption, String format, Object... args);

        Item addData(String caption, Object value);

        <T> Item addData(String caption, Func<T> valueProducer);

        <T> Item addData(String caption, String format, Func<T> valueProducer);
    }

    interface Line {
        Item addData(String caption, String format, Object... args);

        Item addData(String caption, Object value);

        <T> Item addData(String caption, Func<T> valueProducer);

        <T> Item addData(String caption, String format, Func<T> valueProducer);
    }

    interface Log {
        enum DisplayOrder { NEWEST_FIRST, OLDEST_FIRST }

        int getCapacity();

        void setCapacity(int capacity);

        DisplayOrder getDisplayOrder();

        void setDisplayOrder(DisplayOrder displayOrder);

        void add(String entry);

        void add(String format, Object... args);

        void clear();
    }
}
