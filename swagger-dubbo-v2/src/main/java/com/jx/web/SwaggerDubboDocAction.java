package com.jx.web;

import com.jx.web.util.DocUtil;
import io.swagger.annotations.Api;
import io.swagger.models.Swagger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

@Controller
@RequestMapping("swagger-dubbo")
@Api(hidden = true)
public class SwaggerDubboDocAction {

    public static final String DEFAULT_URL = "/api-docs-v2";

    @Value("${server.servlet.context-path:}")
    private String contextPath;
    @Value("${swagger.dubbo.enable:true}")
    private boolean enable = true;

    @RequestMapping(value = DEFAULT_URL, method = RequestMethod.GET, produces = {"text/plain; charset=utf-8"})
    public @ResponseBody ResponseEntity getApiList() throws IOException {
        if (!enable) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Swagger swagger = new Swagger();
        DocUtil.initDubboSwaggerDoc(swagger, contextPath);
        return ResponseEntity.ok(io.swagger.util.Json.mapper().writeValueAsString(swagger));
    }


}
