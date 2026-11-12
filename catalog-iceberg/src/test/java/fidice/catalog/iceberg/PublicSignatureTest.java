package fidice.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.iceberg.Schema;
import org.junit.jupiter.api.Test;

/**
 * The boundary: iceberg-core and its Jackson stay inside this module. Every public class compiled from
 * src/main is scanned, and no type reachable from its public or protected surface (supertypes, fields,
 * record components, constructors, methods, generic arguments and bounds) may come from either.
 */
class PublicSignatureTest {

    private static final List<String> FORBIDDEN = List.of("org.apache.iceberg.", "com.fasterxml.jackson.");

    @Test
    void noIcebergOrJacksonTypeInAPublicSignature() throws IOException, ClassNotFoundException {
        Set<String> scanned = new TreeSet<>();
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : mainClasses()) {
            if (isPublic(type)) {
                scanned.add(type.getName());
                leaks.addAll(leaks(type));
            }
        }

        assertThat(scanned).contains(Metadata.class.getName(), Outcome.class.getName(),
                Outcome.NewMetadata.class.getName(), Facts.class.getName(), Facts.TableFacts.class.getName(),
                Wire.class.getName());
        assertThat(leaks).as("public signatures naming iceberg-core or Jackson types").isEmpty();
    }

    @Test
    void theScanSeesALeak() {
        assertThat(leaks(Leaky.class)).containsExactly(
                Leaky.class.getName() + ".schemas(): java.util.List<org.apache.iceberg.Schema>");
    }

    /** The classes compiled from src/main, loaded without initializing them. */
    private static List<Class<?>> mainClasses() throws IOException, ClassNotFoundException {
        Path root = Path.of(System.getProperty("basedir", "."), "build", "classes", "java", "main");
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                classes.add(Class.forName(name, false, PublicSignatureTest.class.getClassLoader()));
            }
        }
        return classes;
    }

    private static boolean isPublic(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getDeclaringClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static List<String> leaks(Class<?> type) {
        List<String> found = new ArrayList<>();
        String owner = type.getName();
        check(found, owner + " extends", type.getGenericSuperclass());
        for (Type parent : type.getGenericInterfaces()) {
            check(found, owner + " implements", parent);
        }
        for (Field field : type.getDeclaredFields()) {
            if (visible(field.getModifiers()) && !field.isSynthetic()) {
                check(found, owner + "." + field.getName(), field.getGenericType());
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                check(found, owner + "." + component.getName(), component.getGenericType());
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            checkExecutable(found, owner + ".<init>", constructor);
        }
        for (Method method : type.getDeclaredMethods()) {
            checkExecutable(found, owner + "." + method.getName() + "()", method);
            if (visible(method.getModifiers()) && !method.isSynthetic()) {
                check(found, owner + "." + method.getName() + "()", method.getGenericReturnType());
            }
        }
        return found;
    }

    private static void checkExecutable(List<String> found, String where, Executable executable) {
        if (!visible(executable.getModifiers()) || executable.isSynthetic()) {
            return;
        }
        for (Type parameter : executable.getGenericParameterTypes()) {
            check(found, where, parameter);
        }
        for (Type thrown : executable.getGenericExceptionTypes()) {
            check(found, where, thrown);
        }
        for (TypeVariable<?> variable : executable.getTypeParameters()) {
            check(found, where, variable);
        }
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void check(List<String> found, String where, Type type) {
        if (type != null && names(type, new TreeSet<>()).stream().anyMatch(n -> FORBIDDEN.stream().anyMatch(n::startsWith))) {
            found.add(where + ": " + type.getTypeName());
        }
    }

    /** Every class name a type mentions, through arrays, generic arguments, owners and bounds. */
    private static Set<String> names(Type type, Set<String> seen) {
        switch (type) {
            case Class<?> c when c.isArray() -> names(c.getComponentType(), seen);
            case Class<?> c -> seen.add(c.getName());
            case ParameterizedType p -> {
                names(p.getRawType(), seen);
                if (p.getOwnerType() != null) {
                    names(p.getOwnerType(), seen);
                }
                for (Type argument : p.getActualTypeArguments()) {
                    names(argument, seen);
                }
            }
            case GenericArrayType a -> names(a.getGenericComponentType(), seen);
            case WildcardType w -> {
                for (Type bound : w.getUpperBounds()) {
                    names(bound, seen);
                }
                for (Type bound : w.getLowerBounds()) {
                    names(bound, seen);
                }
            }
            case TypeVariable<?> v -> {
                if (seen.add("var " + v.getName() + "@" + v.getGenericDeclaration())) {
                    for (Type bound : v.getBounds()) {
                        names(bound, seen);
                    }
                }
            }
            default -> throw new IllegalArgumentException("unexpected type " + type);
        }
        return seen;
    }

    /** Proves the scan bites: a generic argument is enough to leak. */
    public static final class Leaky {

        public List<Schema> schemas() {
            return List.of();
        }

        List<Schema> hidden() {
            return List.of();
        }
    }
}
