package sia.advertisement.config;

import cn.smartjavaai.face.model.facerec.FaceModel;
import cn.smartjavaai.face.config.FaceModelConfig;
import cn.smartjavaai.face.enums.FaceModelEnum;
import cn.smartjavaai.face.factory.FaceModelFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class SmartJavaAIConfig {

    @Bean
    @Lazy
    public FaceModel faceModel() {
        System.out.println("[SmartJavaAIConfig] 开始加载人脸模型（延迟加载模式）...");
        try {
            FaceModelConfig config = new FaceModelConfig();
            config.setModelEnum(FaceModelEnum.SEETA_FACE6_MODEL);

            ClassPathResource resource = new ClassPathResource("models");
            String modelPath = resource.getFile().getAbsolutePath();
            config.setModelPath(modelPath);

            FaceModel model = FaceModelFactory.getInstance().getModel(config);
            System.out.println("[SmartJavaAIConfig] ✅ 人脸模型加载成功");
            return model;

        } catch (Exception e) {
            System.err.println("[SmartJavaAIConfig] ❌ 人脸模型加载失败: " + e.getMessage());
            e.printStackTrace();
            System.err.println("[SmartJavaAIConfig] ⚠️ 人脸识别功能不可用，将使用兜底方案（boardUserId=1）继续运行");
            return null;
        }
    }
}
