package com.mineclone.game;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure benchmark statistics and strict JSON serialization, independent of rendering. */
public final class BenchMetrics {
    private BenchMetrics() {}

    /** Nearest-rank percentile: p=0 -> minimum, p=1 -> maximum; input is not changed. */
    public static double percentile(double[] values, double p) {
        if (values.length == 0 || !Double.isFinite(p) || p < 0 || p > 1)
            throw new IllegalArgumentException("Percentile needs samples and p in [0,1]");
        double[] sorted = values.clone();
        for (double value : sorted) if (!Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Non-finite or negative sample");
        Arrays.sort(sorted);
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1))];
    }

    public static Map<String, Object> summarize(double[] milliseconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", milliseconds.length);
        if (milliseconds.length == 0) {
            result.put("mean_ms", null); result.put("p50_ms", null); result.put("p95_ms", null);
            result.put("p99_ms", null); result.put("worst_ms", null); result.put("fps", null); result.put("low_1pct_fps", null);
            return result;
        }
        double[] sorted = milliseconds.clone();
        for (double value : sorted) if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("Frame/latency samples must be finite and positive");
        Arrays.sort(sorted);
        double sum = 0, slowestSum = 0;
        int lowCount = Math.max(1, (int) Math.ceil(sorted.length * .01));
        for (int i = 0; i < sorted.length; i++) {
            sum += sorted[i];
            if (i >= sorted.length - lowCount) slowestSum += sorted[i];
        }
        result.put("mean_ms", sum / sorted.length);
        result.put("p50_ms", rank(sorted, .5)); result.put("p95_ms", rank(sorted, .95));
        result.put("p99_ms", rank(sorted, .99)); result.put("worst_ms", sorted[sorted.length - 1]);
        result.put("fps", 1000.0 * sorted.length / sum);
        result.put("low_1pct_fps", 1000.0 * lowCount / slowestSum);
        return result;
    }

    private static double rank(double[] sorted, double p) {
        return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }

    public static double median(double[] values) {
        if (values.length == 0) throw new IllegalArgumentException("No median of empty samples");
        double[] sorted = values.clone(); Arrays.sort(sorted);
        for (double value : sorted) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite sample");
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : sorted[middle - 1] / 2 + sorted[middle] / 2;
    }

    /** Nonnegative duration/counter distribution, including real zero-duration samples. */
    public static Map<String, Object> distribution(double[] values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", values.length);
        if (values.length == 0) {
            for (String key : new String[]{"mean", "p50", "p95", "p99", "max"}) result.put(key, null);
            return result;
        }
        double total = 0;
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid sample");
            total += value;
        }
        result.put("mean", total / values.length);
        result.put("p50", percentile(values, .5)); result.put("p95", percentile(values, .95));
        result.put("p99", percentile(values, .99)); result.put("max", percentile(values, 1));
        return result;
    }

    public static String json(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String || value instanceof Enum<?>) quote(out, value.toString());
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Number number) {
            if ((number instanceof Double || number instanceof Float) && !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("JSON cannot encode non-finite numbers");
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean comma = false;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON object key must be a string");
                if (comma) out.append(','); comma = true;
                quote(out, (String) entry.getKey()); out.append(':'); append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> sequence) {
            out.append('['); boolean comma = false;
            for (Object element : sequence) { if (comma) out.append(','); comma = true; append(out, element); }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) { if (i != 0) out.append(','); append(out, Array.get(value, i)); }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                default -> { if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    /** Primitive sample storage; no per-frame boxing in the measurement path. */
    public static final class Samples {
        private double[] values = new double[65536];
        private int size;
        public void add(double value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }
        public int size() { return size; }
        public double[] values() { return Arrays.copyOf(values, size); }
    }
}
