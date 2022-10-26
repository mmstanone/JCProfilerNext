package jcprofiler.instrumentation.processors;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;

import spoon.processing.AbstractProcessor;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.*;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StatisticsProcessor extends AbstractProcessor<CtReference> {
    // ignore types defined in JavaCard's java.lang package
    private static final List<String> javaCardLangPkg = Collections.unmodifiableList(Stream.of(
            "Exception", "Object", "Throwable", "ArithmeticException", "ArrayIndexOutOfBoundsException",
            "ArrayStoreException", "ClassCastException", "Exception", "IndexOutOfBoundsException",
            "NegativeArraySizeException", "NullPointerException", "RuntimeException", "SecurityException").
            map(x -> "java.lang." + x).collect(Collectors.toList()));

    private List<CtPackageReference> pkgs;

    public SortedMap<Triple<String, String, String>, Integer> getUsedReferences() {
        return Collections.unmodifiableSortedMap(usedReferences);
    }

    private final SortedMap<Triple<String, String, String>, Integer> usedReferences = new TreeMap<>();

    @Override
    public void init() {
        super.init();
        pkgs = getFactory().getModel().filterChildren(CtClass.class::isInstance)
                .map((CtClass<?> c) -> c.getPackage().getReference()).list();
    }

    @Override
    public boolean isToBeProcessed(final CtReference ref) {
        return ref instanceof CtTypeReference || ref instanceof CtFieldReference || ref instanceof CtExecutableReference;
    }

    @Override
    public void process(final CtReference ref) {
        if (ref instanceof CtTypeReference) {
            final CtTypeReference<?> typeRef = (CtTypeReference<?>) ref;

            // ignore null, primitive types and arrays (the array element type will be processed separately)
            if (typeRef.isPrimitive() || typeRef.isArray() || ref.equals(getFactory().Type().NULL_TYPE))
                return;

            final CtElement parent = typeRef.getParent();

            // ignore types of expressions (e.g. assignment or field access) except for explicit type accesses
            // (e.g. in instanceof, when accessing a static field or method or in type casts)
            if (parent instanceof CtExpression && !(parent instanceof CtTypeAccess) &&
                    !((CtExpression<?>)parent).getTypeCasts().contains(typeRef))
                return;

            // ignore types referenced in methods, fields and local variable accesses
            if (parent instanceof CtFieldReference || parent instanceof CtExecutableReference ||
                    parent instanceof CtLocalVariableReference || parent instanceof CtParameterReference ||
                    parent instanceof CtCatchVariableReference)
                return;

            add(typeRef, /* member */ "");
            return;
        }

        if (ref instanceof CtExecutableReference) {
            final CtExecutableReference<?> execRef = ((CtExecutableReference<?>) ref);
            add(execRef.getDeclaringType(), execRef.getSignature());
            return;
        }

        // ref instanceof CtFieldReference
        final CtFieldReference<?> fieldRef = (CtFieldReference<?>) ref;
        final CtTypeReference<?> declTypeRef = fieldRef.getDeclaringType();

        // skip e.g. int[].length
        if (declTypeRef.isPrimitive() || declTypeRef.isArray())
            return;

        add(declTypeRef, fieldRef.getSimpleName());
    }

    private void add(final CtTypeReference<?> cls, final String member) {
        final String qualifiedName = cls.getQualifiedName();
        if (javaCardLangPkg.stream().anyMatch(qualifiedName::startsWith))
            return;

        final CtPackageReference pkg = cls.getPackage();
        if (pkgs.contains(pkg))
            return;

        usedReferences.compute(new ImmutableTriple<>(pkg.getQualifiedName(), cls.getSimpleName(), member),
                (k, v) -> v == null ? 1 : v + 1);
    }
}
