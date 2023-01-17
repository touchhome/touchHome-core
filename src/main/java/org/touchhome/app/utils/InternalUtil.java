package org.touchhome.app.utils;

import java.beans.Introspector;
import java.lang.reflect.Method;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

public final class InternalUtil {

    public static final double GB_DIVIDER = 1024 * 1024 * 1024;

    public static Method findMethodByName(Class clz, String name) {
        String capitalizeName = StringUtils.capitalize(name);
        Method method = MethodUtils.getAccessibleMethod(clz, "get" + capitalizeName);
        if (method == null) {
            method = MethodUtils.getAccessibleMethod(clz, "is" + capitalizeName);
        }
        return method;
    }

    public static String getMethodShortName(Method method) {
        return Introspector.decapitalize(
                method.getName().substring(method.getName().startsWith("is") ? 2 : 3));
    }
}
