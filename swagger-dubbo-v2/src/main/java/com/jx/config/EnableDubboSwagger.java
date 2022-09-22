package com.jx.config;

import org.springframework.context.annotation.ComponentScan;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(value = RUNTIME)
@Target(value = {TYPE})
@ComponentScan(basePackages = {"com.jx.web"})
public @interface EnableDubboSwagger {

}
