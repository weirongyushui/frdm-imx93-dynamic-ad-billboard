package sia.advertisement.service;

import sia.advertisement.entity.AdUser;
import sia.advertisement.mapper.AdUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class AdUserService {

    @Autowired
    private AdUserMapper adUserMapper;

    public AdUser getById(Long id) {
        return adUserMapper.selectById(id);
    }

    public AdUser getByUsername(String username) {
        return adUserMapper.selectByUsername(username);
    }

    public AdUser register(String username, String password, String nickname) {
        AdUser existing = adUserMapper.selectByUsername(username);
        if (existing != null) {
            throw new RuntimeException("用户名已存在");
        }
        AdUser user = new AdUser();
        user.setUsername(username);
        user.setPassword(sha256(password));
        user.setNickname(nickname != null ? nickname : username);
        adUserMapper.insert(user);
        return user;
    }

    public AdUser login(String username, String password) {
        AdUser user = adUserMapper.selectByUsername(username);
        if (user == null) {
            return null;
        }
        if (user.getPassword().equals(sha256(password))) {
            return user;
        }
        return null;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
