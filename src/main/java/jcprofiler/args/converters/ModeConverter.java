package jcprofiler.args.converters;

import com.beust.jcommander.converters.EnumConverter;
import jcprofiler.util.enums.Mode;

/**
 * Parameter converter for the {@link Mode} enum
 */
public class ModeConverter extends EnumConverter<Mode> {
    public ModeConverter(String optionName, Class<Mode> clazz) {
        super(optionName, clazz);
    }
}
