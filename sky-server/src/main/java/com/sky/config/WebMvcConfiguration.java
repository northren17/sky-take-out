package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.json.JacksonObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import java.util.List;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;
    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;

    /**
     * 注册自定义拦截器
     *
     * @param registry
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/employee/login");
        /**
         * 注册自定义用户端拦截器
         * <p>
         * 白名单说明（这些接口必须在未登录状态下可访问）：
         * - /user/user/register  注册
         * - /user/user/captcha   获取图形验证码
         * - /user/user/loginByPassword  账号密码登录
         * - /user/user/login     微信登录
         * - /user/shop/status    用户端查看店铺营业状态
         *
         * @param registry
         */
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/user/**")
                .excludePathPatterns("/user/user/register")
                .excludePathPatterns("/user/user/captcha")
                .excludePathPatterns("/user/user/loginByPassword")
                .excludePathPatterns("/user/user/login")
                .excludePathPatterns("/user/shop/status");
    }

    /**
     * 通过 knife4j + springdoc-openapi 生成接口文档
     * <p>
     * 原 springfox（Swagger2）自 2020 年起停止维护，与 Spring Framework 6 / Spring Boot 3 不兼容，
     * 因此改用 springdoc-openapi（OpenAPI3）。多分组（管理端/用户端）通过配置项
     * springdoc.group-configs 声明，见 application.yml。
     *
     * @return 全局接口文档信息
     */
    @Bean
    public OpenAPI skyOpenAPI() {
        log.info("准备生成接口文档");
        return new OpenAPI()
                .info(new Info()
                        .title("苍穹外卖项目接口文档")
                        .version("2.0")
                        .description("苍穹外卖项目接口文档"));
    }

    /**
     * 设置静态资源映射
     * <p>
     * 本类继承 WebMvcConfigurationSupport，会关闭 Spring Boot 对 Spring MVC 的自动配置，
     * 因此 knife4j / springdoc 的静态资源与接口文档页面必须在此手工映射。
     *
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("开始设置静态资源映射...");
        // knife4j 接口文档页面（classpath:/META-INF/resources/doc.html）
        registry.addResourceHandler("/doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");
        // knife4j 页面依赖的 webjars 资源
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
        // springdoc-openapi 自带的 swagger-ui（/swagger-ui/index.html、/swagger-ui/swagger-ui-bundle.js 等）
        registry.addResourceHandler("/swagger-ui/**", "/swagger-ui.html")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/",
                        "classpath:/META-INF/resources/");
        log.info("后端启动成功");
    }

/*
前后端http消息转化翻译
 */
    @Override
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("自定义消息转化器");
        // 说明：
        // 这里只把 Boot 已注册的 Jackson 转换器的 ObjectMapper 替换为项目自定义的 JacksonObjectMapper
        // （保证 LocalDateTime 序列化为 yyyy-MM-dd HH:mm），而不再像原来那样 new 一个转换器插到列表最前面。
        // 原因：
        //   1. 直接在最前面插入扩展过的 Jackson 转换器，会“抢走”本应由 ByteArrayHttpMessageConverter
        //      处理的二进制响应。springdoc 的 /v3/api-docs 正是返回 byte[] 且声明 produces=application/json，
        //      被 Jackson 处理后会被序列化成 Base64 字符串，导致 knife4j 页面拿不到接口文档；
        //   2. 保留原有转换器顺序，避免影响文件下载、Excel 导出等二进制响应。
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(c -> (MappingJackson2HttpMessageConverter) c)
                .forEach(c -> c.setObjectMapper(new JacksonObjectMapper()));
    }
}
