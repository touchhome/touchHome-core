package org.homio.addon.camera.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UIVideoAction {

    String name();

    String group() default "";

    int order();

    String icon() default "";

    String iconColor() default "inherit";

    VideoActionType type() default VideoActionType.auto;
}
