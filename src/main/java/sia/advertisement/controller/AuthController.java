package sia.advertisement.controller;

import sia.advertisement.entity.AdUser;
import sia.advertisement.service.AdUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class AuthController {

    @Autowired
    private AdUserService adUserService;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/api/auth/register")
    @ResponseBody
    public Map<String, Object> register(@RequestParam String username,
                                         @RequestParam String password,
                                         @RequestParam(required = false) String nickname,
                                         HttpSession session) {
        try {
            AdUser user = adUserService.register(username, password, nickname);
            user.setPassword(null);
            session.setAttribute("loginUser", user);
            return Map.of("success", true, "user", user);
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/api/auth/login")
    @ResponseBody
    public Map<String, Object> login(@RequestParam String username,
                                      @RequestParam String password,
                                      HttpSession session) {
        AdUser user = adUserService.login(username, password);
        if (user == null) {
            return Map.of("success", false, "message", "用户名或密码错误");
        }
        user.setPassword(null);
        session.setAttribute("loginUser", user);
        return Map.of("success", true, "user", user);
    }

    @GetMapping("/api/auth/logout")
    @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        session.removeAttribute("loginUser");
        return Map.of("success", true);
    }

    @GetMapping("/api/auth/current")
    @ResponseBody
    public Map<String, Object> currentUser(HttpSession session) {
        AdUser user = (AdUser) session.getAttribute("loginUser");
        if (user == null) {
            return Map.of("success", false, "message", "未登录");
        }
        return Map.of("success", true, "user", user);
    }
}
