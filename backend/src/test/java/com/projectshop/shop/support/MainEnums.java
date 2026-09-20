package com.projectshop.shop.support;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.projectshop.shop.BackendApplication;

/**
 * {@code main} 이 컴파일된 자리에서 열거형을 걷는다.
 *
 * <p><b>손으로 적은 목록을 대신한다</b>(`Q126`). 적어 두면 <b>열거형이 늘어날 때 빠뜨리는
 * 자리가 생기고</b>, 그것이 실제로 났다 — {@code ResponseEnumCaseTest} 가 자기 주석에
 * 「손으로 적으면 빠뜨린다」고 써 놓고 아홉을 손으로 적고 있었다.
 *
 * <p><b>클래스패스가 아니라 컴파일 산출물에서 걷는다.</b> 클래스패스에는 테스트 클래스와
 * 라이브러리가 같이 있어서 이름으로 거르면 <b>거르는 규칙이 다음에 틀린다.</b>
 *
 * <p>{@code EnumConstraintTest} 가 쓰던 걷기를 여기로 옮긴 것이다 —
 * 두 벌이 되면 한쪽만 고치는 날이 온다.
 */
public final class MainEnums {

    private static final String PACKAGE = "com.projectshop.shop.";

    private MainEnums() {
    }

    /** {@code main} 의 열거형 이름 전부. 패키지 접두어를 뗀 {@code order.ReturnStatus} 모양이다 */
    public static List<String> names() {
        return scan().stream().map(type -> type.getName().substring(PACKAGE.length())).toList();
    }

    /**
     * {@code code()} 를 가진 열거형의 <b>저장값 전부</b>.
     *
     * <p><b>{@code code()} 가 있는 것만 센다.</b> 그 메서드가 있다는 것이 「이 값이 DB 나 바깥에
     * 글자로 나간다」는 표시고, 없는 것은 자바 안에서만 도는 구분이다.
     *
     * @param exemptTypes 빼고 셀 열거형 이름({@link #names()} 와 같은 모양).
     *     <b>비우면 안 되는 자리가 있다</b> — 저장값과 <b>응답에 실려도 되는 값</b>이
     *     같은 열거형에서 나오는 경우다. 부르는 쪽이 이유를 적는다
     */
    public static Set<String> codes(Set<String> exemptTypes) {
        Set<String> codes = new LinkedHashSet<>();

        for (Class<?> type : scan()) {
            if (exemptTypes.contains(type.getName().substring(PACKAGE.length()))) {
                continue;
            }

            Method code = codeMethod(type);
            if (code == null) {
                continue;
            }

            for (Object constant : type.getEnumConstants()) {
                codes.add(String.valueOf(invoke(code, constant)));
            }
        }

        if (codes.isEmpty()) {
            throw new IllegalStateException("저장값을 하나도 못 걸었다 — 걷는 자리가 틀렸다");
        }
        return codes;
    }

    /**
     * 인자 없이 {@code String} 을 주는 {@code code()}. 없으면 {@code null}.
     *
     * <p>열거형이 <b>대개</b> {@code package-private} 이라 접근을 연다. 대안은 이 대조 하나
     * 때문에 접근 범위를 넓히는 것인데, 그러면 「이 열거형을 어디까지 쓰나」를 정한 결정들이
     * 테스트 때문에 풀린다({@code EnumConstraintTest} 가 같은 판단을 했다).
     */
    private static Method codeMethod(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("code");
            if (method.getReturnType() != String.class) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("code() 를 못 불렀다: " + method, e);
        }
    }

    private static List<Class<?>> scan() {
        try {
            Path root = Path.of(BackendApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            try (Stream<Path> files = Files.walk(root)) {
                List<Class<?>> types = files
                        .filter(path -> path.toString().endsWith(".class"))
                        .map(path -> root.relativize(path).toString()
                                .replace('\\', '/')
                                .replace('/', '.')
                                .replaceAll("\\.class$", ""))
                        .filter(name -> name.startsWith(PACKAGE))
                        .map(MainEnums::load)
                        .filter(type -> type != null && type.isEnum())
                        .sorted(java.util.Comparator.comparing(Class::getName))
                        .toList();

                if (types.isEmpty()) {
                    throw new IllegalStateException(
                            "한 개도 못 찾았으면 걷는 자리가 틀린 것이라 대조가 헛돈다: " + root);
                }
                return types;
            }
        } catch (IllegalStateException known) {
            throw known;
        } catch (Exception e) {
            throw new IllegalStateException("main 의 열거형을 못 걸었다", e);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, MainEnums.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
