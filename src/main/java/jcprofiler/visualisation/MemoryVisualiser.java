package jcprofiler.visualisation;

import jcprofiler.args.Args;
import jcprofiler.visualisation.processors.AbstractInsertMeasurementsProcessor;
import jcprofiler.visualisation.processors.InsertMemoryMeasurementsProcessor;

import org.apache.velocity.VelocityContext;

import spoon.reflect.CtModel;

import java.util.Arrays;
import java.util.List;

public class MemoryVisualiser extends AbstractVisualiser {
    public MemoryVisualiser(final Args args, final CtModel model) {
        super(args, model);
    }

    @Override
    public void loadAndProcessMeasurements() {
        super.loadAndProcessMeasurements();
        prepareHeatmap();
    }

    @Override
    public AbstractInsertMeasurementsProcessor getInsertMeasurementsProcessor() {
        return new InsertMemoryMeasurementsProcessor(args, measurements);
    }

    private void prepareHeatmap() {
        String prevActualTrap = null;
        // prepare values for the heatMap
        for (final String line : sourceCode) {
            if (!line.contains("PM.check(PMC.TRAP")) {
                heatmapValues.add(Arrays.asList(null, null));
                continue;
            }

            final int beginPos = line.indexOf('(') + 1 + "PMC.".length();
            final int endPos = line.indexOf(')');
            final String currentTrap = line.substring(beginPos, endPos);

            // unreachable trap or first reachable trap
            if (measurements.get(currentTrap).contains(null) || prevActualTrap == null) {
                heatmapValues.add(Arrays.asList(0.0, 0.0));

                // first reachable processed trap
                if (prevActualTrap == null)
                    prevActualTrap = currentTrap;

                continue;
            }

            final List<Long> prev = measurements.get(prevActualTrap);
            final List<Long> current = measurements.get(currentTrap);

            // get the biggest difference in available memory
            final double trans = (double) Math.max(prev.get(0) - current.get(0), prev.get(1) - current.get(1));
            final double pers = (double) (prev.get(2) - current.get(2));
            heatmapValues.add(Arrays.asList(trans, pers));

            prevActualTrap = currentTrap;
        }
    }

    @Override
    public void prepareVelocityContext(final VelocityContext context) {
        context.put("measureUnit", "B");
        context.put("nonemptyHeatmap", heatmapValues.stream().anyMatch(
                l -> l.stream().anyMatch(e -> e != null && e != 0.0)));
    }
}
