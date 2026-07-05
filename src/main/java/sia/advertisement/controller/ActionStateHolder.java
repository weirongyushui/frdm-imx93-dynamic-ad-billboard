package sia.advertisement.controller;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享状态：ActionController 和 FolderWatchService 共用
 * 存放最新一次匹配结果，供前端 showcase 轮询
 */
@Component
public class ActionStateHolder {

    private final Map<String, Object> lastAction = new ConcurrentHashMap<>();

    public Map<String, Object> getLastAction() {
        return lastAction;
    }

    public synchronized void putAll(Map<String, Object> data) {
        lastAction.putAll(data);
    }

    public void clear() {
        lastAction.clear();
    }
}
