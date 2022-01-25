package org.touchhome.app.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public final class InternalUtil {

    public static Method findMethodByName(Class clz, String name, Class<? extends Annotation> annotationClass) {
        String capitalizeName = StringUtils.capitalize(name);
        Method method = MethodUtils.getAccessibleMethod(clz, "get" + capitalizeName);
        if (method == null) {
            method = MethodUtils.getAccessibleMethod(clz, "is" + capitalizeName);
        }
        if (method != null) {
            if (!method.isAnnotationPresent(annotationClass) && clz.getSuperclass() != null) {
                Method superMethod = findMethodByName(clz.getSuperclass(), name, annotationClass);
                if (superMethod != null) {
                    return superMethod;
                }
            }
        }
        return method;
    }

    public static Method findMethodByName(Class clz, String name) {
        String capitalizeName = StringUtils.capitalize(name);
        Method method = MethodUtils.getAccessibleMethod(clz, "get" + capitalizeName);
        if (method == null) {
            method = MethodUtils.getAccessibleMethod(clz, "is" + capitalizeName);
        }
        return method;
    }

    public static String getMethodShortName(Method method) {
        return Introspector.decapitalize(method.getName().substring(method.getName().startsWith("is") ? 2 : 3));
    }
}
