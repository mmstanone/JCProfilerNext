package jcprofiler.visualisation;

import jcprofiler.args.Args;
import jcprofiler.util.JCProfilerUtil;
import jcprofiler.util.Stage;
import jcprofiler.visualisation.processors.InsertMeasurementsProcessor;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.implement.IncludeRelativePath;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spoon.SpoonAPI;
import spoon.reflect.declaration.CtMethod;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Visualiser {
    private final Args args;
    private final SpoonAPI spoon;

    // CSV header
    private String atr;
    private String profiledMethodSignature;
    private String elapsedTime;
    private String apduHeader;
    private String[] inputDescription;

    private List<String> inputs;
    private final Map<String, List<Long>> measurements = new LinkedHashMap<>();
    private final Map<String, List<Long>> filteredMeasurements = new LinkedHashMap<>();
    private final Map<String, DescriptiveStatistics> filteredStatistics = new LinkedHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(Visualiser.class);

    public Visualiser(final Args args, final SpoonAPI spoon) {
        this.args = args;
        this.spoon = spoon;
    }

    public void loadAndProcessMeasurements() {
        loadCSV();
        filterOutliers();
    }

    private void loadCSV() {
        final Path csv = JCProfilerUtil.checkFile(args.workDir.resolve("measurements.csv"), Stage.profiling);
        log.info("Loading measurements in {} from {}.", JCProfilerUtil.getTimeUnitSymbol(args.timeUnit), csv);

        try (final CSVParser parser = CSVParser.parse(csv, Charset.defaultCharset(), JCProfilerUtil.getCsvFormat())) {
            final Iterator<CSVRecord> it = parser.iterator();

            // parse header
            final List<String> header = it.next().toList();
            profiledMethodSignature = header.get(0);
            atr = header.get(1);
            elapsedTime = header.get(2);
            apduHeader = header.get(3);
            inputDescription = header.get(4).split(":", 2);

            // parse inputs
            inputs = it.next().toList();

            // parse measurements
            do {
                final List<String> line = it.next().toList();
                final List<Long> values = line.stream().skip(1).map(this::convertTime).collect(Collectors.toList());
                measurements.put(line.get(0), values);
            } while (it.hasNext());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void filterOutliers() {
        log.info("Filtering outliers from the loaded measurements.");
        measurements.forEach((k, v) -> {
            final DescriptiveStatistics ds = new DescriptiveStatistics();
            v.stream().filter(Objects::nonNull).map(Long::doubleValue).forEach(ds::addValue);

            if (ds.getN() == 0) {
                filteredMeasurements.put(k, new ArrayList<>());
                filteredStatistics.put(k, ds);
                return;
            }

            final long n = ds.getN();
            final double mean = ds.getMean();
            final double standardDeviation = ds.getStandardDeviation();

            final List<Long> filteredValues = v.stream().map(l -> {
                if (l == null || n == 1)
                    return l;

                // replace outliers with null
                double zValue = Math.abs(l - mean) / standardDeviation;
                if (inputs.size() < 100 && zValue <= 3.)
                    return l;

                if (inputs.size() < 100000 && zValue <= 2.)
                    return l;

                return zValue <= 1. ? l : null;
            }).collect(Collectors.toList());

            filteredMeasurements.put(k, filteredValues);

            final DescriptiveStatistics filteredDs = new DescriptiveStatistics();
            filteredValues.stream().filter(Objects::nonNull).map(Long::doubleValue).forEach(filteredDs::addValue);
            filteredStatistics.put(k, filteredDs);
        });
    }

    private Long convertTime(final String input) {
        if (input.isEmpty())
            return null;

        long nanos = Long.parseLong(input);
        switch (args.timeUnit) {
            case nano:
                return nanos; // noop
            case micro:
                return TimeUnit.NANOSECONDS.toMicros(nanos);
            case milli:
                return TimeUnit.NANOSECONDS.toMillis(nanos);
            case sec:
                return TimeUnit.NANOSECONDS.toSeconds(nanos);
            default:
                throw new RuntimeException("Unreachable statement reached!");
        }
    }

    public void insertMeasurementsToSources() {
        // always recreate the output directory
        final Path outputDir = JCProfilerUtil.getPerfOutputDirectory(args.workDir);
        JCProfilerUtil.recreateDirectory(outputDir);

        log.info("Inserting measurements into sources.");
        spoon.setSourceOutputDirectory(outputDir.toFile());
        spoon.addProcessor(new InsertMeasurementsProcessor(args, measurements, filteredStatistics));
        spoon.process();
        spoon.prettyprint();
    }

    // TODO: must be executed before insertMeasurementsToSources()
    public void generateHTML() {
        final CtMethod<?> method = JCProfilerUtil.getProfiledMethod(spoon, profiledMethodSignature);

        log.info("Initializing Apache Velocity.");
        final Properties props = new Properties();
        props.put(RuntimeConstants.EVENTHANDLER_INCLUDE, IncludeRelativePath.class.getName());
        props.put(RuntimeConstants.RUNTIME_REFERENCES_STRICT, true);
        props.put(RuntimeConstants.RESOURCE_LOADERS, RuntimeConstants.RESOURCE_LOADER_CLASS);
        props.put(RuntimeConstants.RESOURCE_LOADER + '.' + RuntimeConstants.RESOURCE_LOADER_CLASS + ".class",
                ClasspathResourceLoader.class.getName());

        final VelocityEngine velocityEngine = new VelocityEngine(props);
        velocityEngine.init();

        // escape for HTML and strip empty lines
        final List<String> sourceLines = Arrays.stream(StringEscapeUtils.escapeHtml4(method.prettyprint())
                .split(System.lineSeparator())).filter(x -> !x.isEmpty()).collect(Collectors.toList());

        // prepare values for the heatmap
        final List<Double> heatmapValues = sourceLines.stream().map(s -> {
            if (!s.contains("PM.check(PMC.TRAP"))
                return null;

            final int beginPos = s.indexOf('(') + 1 + "PMC.".length();
            final int endPos = s.indexOf(')');
            final DescriptiveStatistics ds = filteredStatistics.get(s.substring(beginPos, endPos));

            // trap is completely unreachable
            if (ds == null)
                return Double.NaN;

            return Math.round(ds.getMean() * 100.) / 100.;
        }).collect(Collectors.toList());

        final VelocityContext context = new VelocityContext();
        context.put("apduHeader", apduHeader);
        context.put("cardATR", atr);
        context.put("code", sourceLines);
        context.put("elapsedTime", elapsedTime);
        context.put("inputDescription", inputDescription);
        context.put("inputs", inputs.stream().map(s -> "'" + s + "'").collect(Collectors.toList()));
        context.put("methodName", profiledMethodSignature);
        context.put("measurements", measurements);
        context.put("filteredMeasurements", filteredMeasurements);
        context.put("heatmapValues", heatmapValues);
        context.put("timeUnit", JCProfilerUtil.getTimeUnitSymbol(args.timeUnit));

        final Path output = args.workDir.resolve("measurements.html");
        log.info("Generating {}.", output);

        try (final Writer writer = new FileWriter(output.toFile())) {
            final Template template = velocityEngine.getTemplate("jcprofiler/visualisation/template.html.vm");
            template.merge(context, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
