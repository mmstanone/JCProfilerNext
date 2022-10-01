package jcprofiler.instrumentation.processors;

import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.Util;

import jcprofiler.util.JCProfilerUtil;
import jcprofiler.args.Args;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class ModifyEntryPointProcessor extends AbstractProfilerProcessor<CtClass<?>> {
    private static final Logger log = LoggerFactory.getLogger(ModifyEntryPointProcessor.class);

    public ModifyEntryPointProcessor(final Args args) {
        super(args);
    }

    @Override
    public boolean isToBeProcessed(final CtClass<?> cls) {
        // isEntryPoint && (!entryPointArg.isEmpty() => cls.SimpleName == entryPointArg)
        return JCProfilerUtil.isTypeEntryPoint(cls) &&
                (args.entryPoint.isEmpty() || cls.getQualifiedName().equals(args.entryPoint));
    }

    private CtMethod<Void> getProcessMethod(final CtClass<?> cls) {
        CtMethod<Void> processMethod;
        CtTypeReference<?> clsRef = cls.getReference();

        do {
            processMethod = clsRef.getTypeDeclaration().filterChildren(
                    (CtMethod<Void> m) -> m.getSignature().equals("process(javacard.framework.APDU)") &&
                            m.getType().equals(getFactory().createCtTypeReference(Void.TYPE)) &&
                            !m.isAbstract() && m.getBody() != null).first();
            clsRef = clsRef.getSuperclass();
        } while (clsRef != null && processMethod == null);

        if (processMethod == null)
            throw new RuntimeException(String.format(
                    "Class %s inherits from %s but does not implement the 'process(javacard.framework.APDU)' method!",
                    cls.getQualifiedName(), cls.getSuperclass().getQualifiedName()));
        return processMethod;
    }

    @Override
    public void process(final CtClass<?> cls) {
        log.info("Instrumenting entry point class {}.", cls.getQualifiedName());
        final CtMethod<Void> processMethod = getProcessMethod(cls);
        final CtField<Byte> insPerfSetStop = addInsPerfSetStopField(processMethod);
        addInsPerfSetStopHandler(processMethod, insPerfSetStop);
    }

    private CtField<Byte> addInsPerfSetStopField(final CtMethod<Void> processMethod) {
        final CtType<?> declaringCls = processMethod.getDeclaringType();

        // private static final byte INS_PERF_SETSTOP = (byte) ${JCProfilerUtil.INS_PERF_SETSTOP}
        final CtTypeReference<Byte> byteRef = getFactory().createCtTypeReference(Byte.TYPE);

        Optional<CtField<?>> existingInsPerfSetStop = declaringCls.getFields().stream().filter(
                f -> f.getSimpleName().equals("INS_PERF_SETSTOP")).findFirst();
        if (existingInsPerfSetStop.isPresent()) {
            final CtField<?> insPerfSetStop = existingInsPerfSetStop.get();
            log.info("Existing INS_PERF_SETSTOP field found at {}.", insPerfSetStop.getPosition());

            if (!insPerfSetStop.getType().equals(byteRef))
                throw new RuntimeException(String.format(
                        "Existing INS_PERF_SETSTOP field has type %s! Expected: %s",
                        insPerfSetStop.getType().getQualifiedName(), Byte.TYPE.getTypeName()));

            @SuppressWarnings("unchecked") // the runtime check is above
            final CtField<Byte> insPerfSetStopCasted = (CtField<Byte>) insPerfSetStop;

            // private or public modifier does NOT make a difference in this case
            if (!insPerfSetStopCasted.hasModifier(ModifierKind.FINAL) ||
                    !insPerfSetStopCasted.hasModifier(ModifierKind.STATIC))
                throw new RuntimeException(
                        "Existing INS_PERF_SETSTOP field is not static and final! Got: " +
                        // Set<ModifierKind> does not have a stable ordering
                        insPerfSetStopCasted.getModifiers().stream().sorted().collect(Collectors.toList()));

            final CtLiteral<? extends Number> lit = insPerfSetStopCasted.getAssignment().partiallyEvaluate();
            if (lit.getValue().byteValue() != JCProfilerUtil.INS_PERF_HANDLER)
                throw new RuntimeException(String.format(
                        "Existing INS_PERF_SETSTOP field has %s as initializer! Expected: (byte) 0x%02x",
                        insPerfSetStopCasted.getAssignment().prettyprint(), JCProfilerUtil.INS_PERF_HANDLER));

            return insPerfSetStopCasted;
        }

        // create new INS_PERF_SETSTOP field
        final CtField<Byte> insPerfSetStop = getFactory().createCtField(
                "INS_PERF_SETSTOP", byteRef, "", ModifierKind.PUBLIC, ModifierKind.FINAL, ModifierKind.STATIC);

        // create and set the initializer
        final CtLiteral<Integer> initializer = getFactory().createLiteral(Byte.toUnsignedInt(JCProfilerUtil.INS_PERF_HANDLER));
        initializer.setBase(LiteralBase.HEXADECIMAL);
        initializer.addTypeCast(byteRef);

        @SuppressWarnings("unchecked")
        // Unfortunately, this is the best solution we have since SPOON does not reflect type casts in type parameters.
        final CtLiteral<Byte> initializerCasted = (CtLiteral<Byte>) (Object) initializer;
        insPerfSetStop.setAssignment(initializerCasted);

        // insert the field
        declaringCls.addField(0, insPerfSetStop);
        log.debug("Added INS_PERF_SETSTOP field to {}.", declaringCls.getQualifiedName());
        return insPerfSetStop;
    }

    private void addInsPerfSetStopHandler(final CtMethod<Void> processMethod, final CtField<Byte> insPerfSetStop) {
        final CtTypeReference<Short> shortRef = getFactory().createCtTypeReference(Short.TYPE);

        // PM.m_perfStop
        final CtFieldWrite<Short> fieldWrite = getFactory().createFieldWrite();
        {
            final CtField<?> perfStopField = PM.getField("m_perfStop");

            if (!perfStopField.getType().equals(shortRef))
                throw new RuntimeException(String.format(
                        "PM.m_perfStop has type %s! Expected: short", perfStopField.getType().getQualifiedName()));

            @SuppressWarnings("unchecked") // the runtime check is above
            final CtField<Short> perfStopFieldCasted = (CtField<Short>) perfStopField;

            fieldWrite.setTarget(getFactory().createTypeAccess(PM.getReference()));
            fieldWrite.setVariable(perfStopFieldCasted.getReference());
        }

        // ${param}.getBuffer()
        CtInvocation<?> getBufferInvocation;
        try {
            final Method getBufferMethod = APDU.class.getMethod("getBuffer");
            final CtParameter<?> apduParam = processMethod.getParameters().get(0);
            final CtVariableAccess<?> apduParamRead = getFactory().createVariableRead(apduParam.getReference(), false);
            getBufferInvocation = getFactory().createInvocation(
                    apduParamRead, getFactory().Method().createReference(getBufferMethod));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        // Util.getShort(${param}.getBuffer(), ISO7816.OFFSET_CDATA)
        CtInvocation<Short> makeShortInvocation;
        try {
            // get javacard.framework.ISO7816.OFFSET_CDATA
            final Class<?> iso7816Class = ISO7816.class;
            final Field offsetCdata = iso7816Class.getField("OFFSET_CDATA");

            final CtFieldRead<Short> offsetFieldRead = getFactory().createFieldRead();
            offsetFieldRead.setTarget(getFactory().createTypeAccess(getFactory().createCtTypeReference(iso7816Class)));
            offsetFieldRead.setVariable(getFactory().Field().createReference(offsetCdata));

            // get javacard.framework.Util.getShort(byte[] bArray, short bOff)
            final Class<?> utilClass = Util.class;
            final Method getShortMethod = utilClass.getMethod("getShort", byte[].class, Short.TYPE);

            // create Util.getShort(${apdu}.getBuffer(), ISO7816.OFFSET_CDATA)
            makeShortInvocation = getFactory().createInvocation(
                    getFactory().createTypeAccess(getFactory().Class().createReference(utilClass)),
                    getFactory().Method().createReference(getShortMethod),
                    getBufferInvocation, offsetFieldRead);
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        // {
        //     PM.m_perfStop = Util.getShort(${param}.getBuffer(), ISO7816.OFFSET_CDATA);
        //     return;
        // }
        final CtBlock<Void> perfStopBlock = getFactory().createBlock();
        {
            final CtAssignment<Short, Short> perfStopAssign = getFactory().createAssignment();
            perfStopAssign.setType(shortRef);
            perfStopAssign.setAssigned(fieldWrite);
            perfStopAssign.setAssignment(makeShortInvocation);

            perfStopBlock.addStatement(perfStopAssign);
            perfStopBlock.addStatement(getFactory().createReturn());
        }

        // ${param}.getBuffer()[ISO7816.OFFSET_INS]
        CtArrayRead<Byte> apduBufferRead;
        try {
            // get javacard.framework.ISO7816.OFFSET_INS
            final Class<?> iso7816Class = ISO7816.class;
            final Field offsetIns = iso7816Class.getField("OFFSET_INS");

            final CtFieldRead<Integer> offsetFieldRead = getFactory().createFieldRead();
            offsetFieldRead.setTarget(getFactory().createTypeAccess(getFactory().createCtTypeReference(iso7816Class)));
            offsetFieldRead.setVariable(getFactory().Field().createReference(offsetIns));

            apduBufferRead = getFactory().createArrayRead();
            apduBufferRead.setIndexExpression(offsetFieldRead);
            apduBufferRead.setTarget(getBufferInvocation);
            apduBufferRead.setType(getFactory().createCtTypeReference(Byte.TYPE));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        // ${param}.getBuffer()[ISO7816.OFFSET_INS] == INS_PERF_SETSTOP
        CtBinaryOperator<Boolean> insEqCond;
        {
            final CtTypeReference<?> processMethodDeclTypeRed = processMethod.getDeclaringType().getReference();
            final CtTypeReference<?> insPerfSetStopDeclTypeRef = insPerfSetStop.getDeclaringType().getReference();

            final CtFieldRead<Byte> insPerfSetStopFieldRead = getFactory().createFieldRead();
            insPerfSetStopFieldRead.setTarget(getFactory().createTypeAccess(insPerfSetStopDeclTypeRef,
                    /* implicit */ processMethodDeclTypeRed.equals(insPerfSetStopDeclTypeRef)));
            insPerfSetStopFieldRead.setVariable(getFactory().Field().createReference(insPerfSetStop));

            insEqCond = getFactory().createBinaryOperator(apduBufferRead, insPerfSetStopFieldRead, BinaryOperatorKind.EQ);
            insEqCond.setType(getFactory().createCtTypeReference(Boolean.TYPE));
        }

        // if (${param}.getBuffer()[ISO7816.OFFSET_INS] == INS_PERF_SETSTOP) {
        //     PM.m_perfStop = Util.getShort(${param}.getBuffer(), ISO7816.OFFSET_CDATA);
        //     return;
        // }
        final CtIf ifStatement = getFactory().createIf();
        ifStatement.setCondition(insEqCond);
        ifStatement.setThenStatement(perfStopBlock);

        final CtBlock<Void> processMethodBody = processMethod.getBody();

        Optional<CtIf> maybeExistingIfStatement = processMethodBody.getStatements().stream()
                .filter(ifStatement::equals).map(CtIf.class::cast).findAny();
        if (maybeExistingIfStatement.isPresent()) {
            log.info("Existing INS_PERF_SETSTOP handler found at {}.", maybeExistingIfStatement.get().getPosition());
            return;
        }

        maybeExistingIfStatement = processMethodBody.getStatements().stream().filter(CtIf.class::isInstance)
                .map(CtIf.class::cast).filter(i -> i.getCondition().equals(ifStatement.getCondition())).findAny();
        if (maybeExistingIfStatement.isPresent()) {
            final CtIf existingIf = maybeExistingIfStatement.get();
            log.info("Existing INS_PERF_SETSTOP handler found at {}.", existingIf.getPosition());
            throw new RuntimeException(String.format(
                    "The body of the INS_PERF_SETSTOP handle has unexpected contents:%n%s%nExpected:%n%s%n",
                    existingIf.getThenStatement().prettyprint(), ifStatement.getThenStatement().prettyprint()));
        }

        processMethodBody.addStatement(0, ifStatement);
        log.debug("Added INS_PERF_SETSTOP handler as the first statement to {}.",
                JCProfilerUtil.getFullSignature(processMethod));
    }
}
