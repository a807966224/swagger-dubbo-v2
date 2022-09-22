package com.jx.web.util;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.annotations.*;
import io.swagger.converter.ModelConverters;
import io.swagger.models.*;
import io.swagger.models.parameters.AbstractSerializableParameter;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.ParameterProcessor;
import io.swagger.util.PathUtils;
import io.swagger.util.PrimitiveType;
import io.swagger.util.ReflectionUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PrioritizedParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DocUtil {

    private static Logger logger = LoggerFactory.getLogger(DocUtil.class);

    private static final ParameterNameDiscoverer parameterNameDiscover;

    static {
        parameterNameDiscover = new PrioritizedParameterNameDiscoverer();
        ((PrioritizedParameterNameDiscoverer) parameterNameDiscover).addDiscoverer(new LocalVariableTableParameterNameDiscoverer());
        ((PrioritizedParameterNameDiscoverer) parameterNameDiscover).addDiscoverer(new StandardReflectionParameterNameDiscoverer());
    }

    public static void initDubboSwaggerDoc(Swagger swagger, String contextPath) {
        List<ServiceBean> serviceBeans = new ArrayList<>();

        setServiceBean(serviceBeans);

        if (CollectionUtils.isEmpty(serviceBeans)) {
            return;
        }

        for (ServiceBean serviceBean : serviceBeans) {
            Method[] interfaceMethods = serviceBean.getInterfaceClass().getMethods();
            for (Method interfaceMethod : interfaceMethods) {
                setSwaggerDoc(swagger, interfaceMethod, contextPath);
            }

        }
    }

    private static void setServiceBean(List<ServiceBean> serviceBeans) {
        try {
            // 低版本中使用小写的contexts
            Field field = SpringExtensionFactory.class.getDeclaredField("CONTEXTS");
            field.setAccessible(true);
            Set<ApplicationContext> contexts = (Set<ApplicationContext>) field.get(new SpringExtensionFactory());
            for (ApplicationContext context : contexts) {
                serviceBeans.addAll(context.getBeansOfType(ServiceBean.class).values().stream().filter(bean -> {
                    Annotation apiAnnotation = bean.getInterfaceClass().getAnnotation(Api.class);
                    return null != apiAnnotation;
                }).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            logger.error("Get All Dubbo Service Error", e);
        }
    }

    private static void setSwaggerDoc(Swagger swagger, Method interfaceMethod, String contextPath) {
        Api api = interfaceMethod.getDeclaringClass().getAnnotation(Api.class);
        ApiOperation apiOperation = ReflectionUtils.getAnnotation(interfaceMethod, ApiOperation.class);
        if (Objects.isNull(apiOperation) || Objects.isNull(api)) return;

        Operation operation = new Operation();
        // 使用nickname作为访问路径
        String nickname2Path = Optional.ofNullable(apiOperation.nickname()).orElse(interfaceMethod.getName());
        String pathKey = PathUtils.collectPath(contextPath, nickname2Path);
        Path path = swagger.getPath(pathKey);
        if (path == null) {
            path = new Path();
            swagger.path(pathKey, path);
        }

        setOperationInfo(swagger, interfaceMethod, path, operation, api, apiOperation);
    }

    private static void setOperationInfo(Swagger swagger, Method interfaceMethod
            , Path path, Operation operation
            , Api api, ApiOperation apiOperation) {
        // 接口文档分类
        String[] tags = Optional.ofNullable(api.tags()).orElse(new String[]{interfaceMethod.getDeclaringClass().getSimpleName()});
        // 接口文档描述信息
        String value = Optional.ofNullable(apiOperation.value()).orElse(interfaceMethod.getName());
        // 访问方式
        String httpMethod = !Arrays.stream(HttpMethod.values())
                .filter(e -> e.toString().equalsIgnoreCase(apiOperation.httpMethod())).findAny().isPresent()
                ? HttpMethod.POST.toString() : apiOperation.httpMethod();
        // 请求参数
        try {
            String[] parameterNames = parameterNameDiscover.getParameterNames(interfaceMethod);
            Type[] genericParameterTypes = interfaceMethod.getGenericParameterTypes();
            Class<?>[] parameterTypes = interfaceMethod.getParameterTypes();
            Annotation[][] parameterAnnotations = interfaceMethod.getParameterAnnotations();
            Annotation[][] interfaceParamAnnotations = interfaceMethod.getParameterAnnotations();
            for (int i = 0; i < genericParameterTypes.length; i++) {
                applyParametersV2(swagger, operation,
                        null == parameterNames ? null : parameterNames[i], genericParameterTypes[i], parameterTypes[i],
                        parameterAnnotations[i], interfaceParamAnnotations[i]);
            }
        } catch (SecurityException e) {
            logger.error("Get Dubbo Service Parameter Error", e);
        }
        // 响应参数
        final Map<Integer, Response> result = new HashMap<Integer, Response>();
        if (apiOperation != null && org.apache.commons.lang3.StringUtils.isNotBlank(apiOperation.responseReference())) {
            final Response response = new Response().description(org.apache.commons.lang3.StringUtils.EMPTY);
            response.schema(new RefProperty(apiOperation.responseReference()));
            result.put(apiOperation.code(), response);
        }

        final Type responseType = getResponseType(interfaceMethod);
        if (isValidResponse(responseType)) {
            final Property property = ModelConverters.getInstance().readAsProperty(responseType);
            if (property != null) {
                final Property responseProperty = ContainerWrapper
                        .wrapContainer(getResponseContainer(apiOperation), property);
                final int responseCode = apiOperation == null ? 200 : apiOperation.code();
                final Map<String, Property> defaultResponseHeaders = apiOperation == null
                        ? Collections.<String, Property>emptyMap()
                        : parseResponseHeaders(swagger, apiOperation.responseHeaders());
                final Response response = new Response().description(org.apache.commons.lang3.StringUtils.EMPTY)
                        .schema(responseProperty).headers(defaultResponseHeaders);
                result.put(responseCode, response);
                appendModels(swagger, responseType);
            }
        }

        final ApiResponses responseAnnotation = ReflectionUtils.getAnnotation(interfaceMethod,
                ApiResponses.class);
        if (responseAnnotation != null) {
            for (ApiResponse apiResponse : responseAnnotation.value()) {
                final Map<String, Property> responseHeaders = parseResponseHeaders(swagger,
                        apiResponse.responseHeaders());

                final Response response = new Response().description(apiResponse.message())
                        .headers(responseHeaders);

                if (org.apache.commons.lang3.StringUtils.isNotEmpty(apiResponse.reference())) {
                    response.schema(new RefProperty(apiResponse.reference()));
                } else if (!ReflectionUtils.isVoid(apiResponse.response())) {
                    final Type type = apiResponse.response();
                    final Property property = ModelConverters.getInstance().readAsProperty(type);
                    if (property != null) {
                        response.schema(ContainerWrapper
                                .wrapContainer(apiResponse.responseContainer(), property));
                        appendModels(swagger, type);
                    }
                }
                result.put(apiResponse.code(), response);
            }
        }

        for (Map.Entry<Integer, Response> responseEntry : result.entrySet()) {
            if (responseEntry.getKey() == 0) {
                operation.defaultResponse(responseEntry.getValue());
            } else {
                operation.response(responseEntry.getKey(), responseEntry.getValue());
            }
        }

        operation.tags(Stream.of(tags).collect(Collectors.toList()))
                .description(value)
                .summary(value)
                .deprecated(ReflectionUtils.getAnnotation(interfaceMethod, Deprecated.class) != null)
                .consumes(StringUtils.isBlank(apiOperation.consumes()) ? "application/json" : apiOperation.consumes())
        ;
        // 设置Path的请求方式
        path.set(httpMethod.toLowerCase(), operation);
    }

    private static void applyParametersV2(Swagger swagger, Operation operation, String name,
                                   Type type, Class<?> cls, Annotation[] annotations, Annotation[] interfaceParamAnnotations) {
        Annotation apiParam = null;
        if (annotations != null) {
            for (Annotation annotation : interfaceParamAnnotations) {
                if (annotation instanceof ApiParam) {
                    apiParam = annotation;
                    break;
                }
            }
            if (null == apiParam) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof ApiParam) {
                        apiParam = annotation;
                        break;
                    }
                }
            }
        }
        final Parameter parameter = readParam(swagger, type, cls,
                null == apiParam ? null : (ApiParam) apiParam);
        if (parameter != null) {
            parameter.setName(null == name ? parameter.getName() : name);
            operation.parameter(parameter);
        }
    }

    private static Parameter readParam(Swagger swagger, Type type, Class<?> cls, ApiParam param) {
        PrimitiveType fromType = PrimitiveType.fromType(type);
        final Parameter para = null == fromType ? new BodyParameter() : new QueryParameter();
        Parameter parameter;
        if (type == null) {
            if (null == param) {
                parameter = ParameterProcessor.applyAnnotations(swagger, para,
                        String.class, new ArrayList<Annotation>());
            } else {
                parameter = ParameterProcessor.applyAnnotations(swagger, para,
                        String.class, Collections.<Annotation>singletonList(param));
            }
        } else {
            if (null == param) {
                parameter = ParameterProcessor.applyAnnotations(swagger, para,
                        type, new ArrayList<Annotation>());
            } else {
                parameter = ParameterProcessor.applyAnnotations(swagger, para,
                        type, Collections.<Annotation>singletonList(param));
            }
        }
        if (parameter instanceof AbstractSerializableParameter) {
            final AbstractSerializableParameter<?> p = (AbstractSerializableParameter<?>) parameter;
            if (p.getType() == null) p.setType(null == fromType ? "string" : fromType.getCommonName());
            p.setRequired(p.getRequired() == true ? true : cls.isPrimitive());
        } else {
            //hack: Get the from data model paramter from BodyParameter
            BodyParameter bp = (BodyParameter) parameter;
//            bp.setIn("formData");
            bp.setIn("body");
        }
        return parameter;
    }

    private static boolean isValidResponse(Type type) {
        final JavaType javaType = TypeFactory.defaultInstance().constructType(type);
        return !ReflectionUtils.isVoid(javaType);
    }

    private static Type getResponseType(Method method) {
        final ApiOperation apiOperation = ReflectionUtils.getAnnotation(method, ApiOperation.class);
        if (apiOperation != null && !ReflectionUtils.isVoid(apiOperation.response())) {
            return apiOperation.response();
        } else {
            return method.getGenericReturnType();
        }
    }

    private static String getResponseContainer(ApiOperation apiOperation) {
        return apiOperation == null ? null
                : org.apache.commons.lang3.StringUtils.defaultIfBlank(apiOperation.responseContainer(), null);
    }

    enum ContainerWrapper {
        LIST("list") {
            @Override
            protected Property doWrap(Property property) {
                return new ArrayProperty(property);
            }
        },
        ARRAY("array") {
            @Override
            protected Property doWrap(Property property) {
                return new ArrayProperty(property);
            }
        },
        MAP("map") {
            @Override
            protected Property doWrap(Property property) {
                return new MapProperty(property);
            }
        },
        SET("set") {
            @Override
            protected Property doWrap(Property property) {
                ArrayProperty arrayProperty = new ArrayProperty(property);
                arrayProperty.setUniqueItems(true);
                return arrayProperty;
            }
        };

        private final String container;

        ContainerWrapper(String container) {
            this.container = container;
        }

        public static Property wrapContainer(String container, Property property,
                                             ContainerWrapper... allowed) {
            final Set<ContainerWrapper> tmp = allowed.length > 0
                    ? EnumSet.copyOf(Arrays.asList(allowed))
                    : EnumSet.allOf(ContainerWrapper.class);
            for (ContainerWrapper wrapper : tmp) {
                final Property prop = wrapper.wrap(container, property);
                if (prop != null) {
                    return prop;
                }
            }
            return property;
        }

        public Property wrap(String container, Property property) {
            if (this.container.equalsIgnoreCase(container)) {
                return doWrap(property);
            }
            return null;
        }

        protected abstract Property doWrap(Property property);
    }

    private static Map<String, Property> parseResponseHeaders(Swagger swagger,
                                                              ResponseHeader[] headers) {
        Map<String, Property> responseHeaders = null;
        for (ResponseHeader header : headers) {
            final String name = header.name();
            if (org.apache.commons.lang3.StringUtils.isNotEmpty(name)) {
                if (responseHeaders == null) {
                    responseHeaders = new HashMap<String, Property>();
                }
                final Class<?> cls = header.response();
                if (!ReflectionUtils.isVoid(cls)) {
                    final Property property = ModelConverters.getInstance().readAsProperty(cls);
                    if (property != null) {
                        final Property responseProperty = ContainerWrapper.wrapContainer(
                                header.responseContainer(), property, ContainerWrapper.ARRAY,
                                ContainerWrapper.LIST, ContainerWrapper.SET);
                        responseProperty.setDescription(header.description());
                        responseHeaders.put(name, responseProperty);
                        appendModels(swagger, cls);
                    }
                }
            }
        }
        return responseHeaders;
    }

    private static void appendModels(Swagger swagger, Type type) {
        final Map<String, Model> models = ModelConverters.getInstance().readAll(type);
        for (Map.Entry<String, Model> entry : models.entrySet()) {
            swagger.model(entry.getKey(), entry.getValue());
        }
    }

}
