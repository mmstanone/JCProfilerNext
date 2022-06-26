package jcprofiler.profiling;

import com.github.curiousoddman.rgxgen.RgxGen;
import cz.muni.fi.crocs.rcard.client.CardManager;
import cz.muni.fi.crocs.rcard.client.Util;
import jcprofiler.args.Args;
import pro.javacard.gp.ISO7816;
import spoon.SpoonAPI;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author Lukáš Zaoral and Petr Svenda
 */
public class Profiler {
    private static final byte INS_PERF_SETSTOP = (byte) 0xf5;
    private static final short PERF_START = 0x0001;
    private final Args args;
    private final CardManager cardManager;
    private final CtModel model;
    private final Map<Short, String> trapNameMap = new LinkedHashMap<>(); // to preserve insertion order
    private final Set<String> failedTraps = new HashSet<>();
    // map between trap id key and a list of measurements
    private final Map<String, List<Long>> measurements = new LinkedHashMap<>(); // to preserve insertion order
    private short finalTrapID;

    public Profiler(final Args args, final CardManager cardManager, final SpoonAPI spoon) {
        this.args = args;
        this.cardManager = cardManager;
        this.model = spoon.getModel();

        buildPerfMapping();
    }

    private String getPerfStopName(final short trapID) {
        return trapNameMap.get(trapID);
    }

    private void buildPerfMapping() {
        // TODO: what about more classes with such name?
        // idea: make PM and PMC both inherit from our special and empty abstract class
        final CtClass<?> pmc = model.filterChildren((CtClass<?> c) -> c.getSimpleName().equals("PMC")).first();

        // FIXME: this may fail, may get more methods, die to dependence on mangling
        final String regex = String.format(".*_%s_argb_.*_arge_[^_]*", args.method);
        final List<CtField<Short>> traps = pmc.getElements((CtField<Short> f) -> f.getSimpleName().matches(regex));
        if (traps.isEmpty())
            throw new RuntimeException(String.format(
                    "Extraction of traps from PM for %s failed!", args.method));

        for (final CtField<Short> f : traps) {
            final CtLiteral<Integer> evaluated = f.getDefaultExpression().partiallyEvaluate();
            trapNameMap.put(evaluated.getValue().shortValue(), f.getSimpleName());
        }
    }

    public Map<String, List<Long>> profile() {
        try {
            // reset if possible and erase any previous performance stop and reset if possible
            resetApplet();
            setTrap(PERF_START);

            // TODO: add input to measured data
            // TODO: more formats? hamming weight?

            // TODO: print seed for reproducibility
            final Random rdn = new Random();
            final RgxGen rgxGen = new RgxGen(args.inputRegex);

            System.out.println("\n-------------- Performance profiling start --------------\n\n");

            for (int repeat = 0; repeat < args.repeat_count; repeat++) {
                byte[] arr = Util.hexStringToByteArray(rgxGen.generate(rdn));
                final CommandAPDU triggerAPDU = new CommandAPDU(args.cla, args.inst, args.p1, args.p2, arr);

                System.out.printf("Profiling %s, round: %d%n", args.method, repeat + 1);
                System.out.printf("APDU: %s%n", Util.toHex(triggerAPDU.getBytes()));

                profileSingleStep(triggerAPDU);
            }

            System.out.println("\n-------------- Performance profiling finished --------------\n\n");
            System.out.print("Disconnecting from card...");

            // TODO: what difference is between true and false? javax.smartcardio.Card javadoc is now very helpful
            cardManager.disconnect(true);
            System.out.println(" Done.");

            if (failedTraps.isEmpty()) {
                System.out.println("#######################################");
                System.out.println("ALL PERFORMANCE TRAPS REACHED CORRECTLY");
                System.out.println("#######################################");
            } else {
                System.out.println("################################################");
                System.out.println("!!! SOME PERFORMANCE TRAPS NOT ALWAYS REACHED!!!");
                System.out.println("################################################");
                failedTraps.forEach(System.out::println);
            }

            // sanity check
            measurements.values().forEach(v -> {
                if (v.size() != args.repeat_count)
                    throw new RuntimeException(String.format("%s.size() == %d", v, args.repeat_count));
            });

            return Collections.unmodifiableMap(measurements);
        } catch (CardException e) {
            throw new RuntimeException(e);
        }
    }

    private void setTrap(short trapID) throws CardException {
        CommandAPDU setTrap = new CommandAPDU(args.cla, INS_PERF_SETSTOP, 0, 0, Util.shortToByteArray(trapID));
        ResponseAPDU response = cardManager.transmit(setTrap);
        if (response.getSW() != ISO7816.SW_NO_ERROR)
            throw new RuntimeException(String.format(
                    "Setting \"%s\" trap failed with SW %d", getPerfStopName(trapID), response.getSW()));
    }

    private void resetApplet() throws CardException {
        if (args.cleanupInst == null)
            return;

        CommandAPDU reset = new CommandAPDU(args.cla, args.cleanupInst, 0, 0, 0);
        ResponseAPDU response = cardManager.transmit(reset);
        if (response.getSW() != ISO7816.SW_NO_ERROR)
            throw new RuntimeException("Resetting the applet failed with SW " + response.getSW());
    }

    private void profileSingleStep(CommandAPDU triggerAPDu) throws CardException {
        Duration prevTransmitDuration = Duration.ZERO;
        Duration currentTransmitDuration;

        for (short trapID : trapNameMap.keySet()) {
            // set performance trap
            setTrap(trapID);

            // execute target operation
            ResponseAPDU response = cardManager.transmit(triggerAPDu);

            // Check expected error to be equal performance trap
            if (response.getSW() != trapID) {
                // we have not reached expected performance trap
                failedTraps.add(getPerfStopName(trapID));
                measurements.computeIfAbsent(getPerfStopName(trapID), k -> new ArrayList<>()).add(null);
                continue;
            }

            // TODO: set precision with a commandline-option
            currentTransmitDuration = cardManager.getLastTransmitTimeDuration();

            measurements.computeIfAbsent(getPerfStopName(trapID), k -> new ArrayList<>())
                    .add(TimeUnit.NANOSECONDS.toMicros(currentTransmitDuration.minus(prevTransmitDuration).toNanos()));

            prevTransmitDuration = currentTransmitDuration;

            // free memory after command
            resetApplet();
        }
    }
}
