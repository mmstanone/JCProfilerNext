package jcprofiler.args.converters;

import com.beust.jcommander.converters.EnumConverter;
import jcprofiler.util.enums.TimeUnit;

/**
 * Parameter converter for the {@link TimeUnit} enum
 */
public class TimeUnitConverter extends EnumConverter<TimeUnit> {
    public TimeUnitConverter(String optionName, Class<TimeUnit> clazz) {
        super(optionName, clazz);
    }
}
