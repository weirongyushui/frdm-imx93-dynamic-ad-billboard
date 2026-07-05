package sia.advertisement.config;

import sia.advertisement.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/register",
                        "/preview",
                        "/showcase",
                        "/vault",
                        "/photo/**",
                        "/watch-photos/**",
                        "/uploads/**",
                        "/api/auth/**",
                        "/api/action/**",
                        "/api/watch/**",
                        "/api/image/**",
                        "/api/ad/projects/showcase",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/error"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location;
        java.io.File baseDir = new java.io.File(uploadDir);
        if (baseDir.isAbsolute()) {
            location = baseDir.toURI().toString();
        } else {
            String userDir = System.getProperty("user.dir");
            location = new java.io.File(userDir, uploadDir).toURI().toString();
        }
        registry.addResourceHandler("/photo/**")
                .addResourceLocations(location);
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
