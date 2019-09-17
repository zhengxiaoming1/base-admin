package cn.huanzi.qch.baseadmin.config.security;

import cn.huanzi.qch.baseadmin.sys.sysuser.service.SysUserService;
import cn.huanzi.qch.baseadmin.sys.sysuser.vo.SysUserVo;
import cn.huanzi.qch.baseadmin.util.AesUtil;
import cn.huanzi.qch.baseadmin.util.IpUtil;
import cn.huanzi.qch.baseadmin.util.RsaUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 登录成功处理，登陆成功后还需要验证账号的有效性
 */
@Component
@Slf4j
public class LoginSuccessHandlerConfig implements AuthenticationSuccessHandler {
    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private SysUserService sysUserService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Authentication authentication) throws IOException, ServletException {
        //从全局注册表中获取所有登陆用户，做用户单登陆判断
        ArrayList<String> allUserNameList = new ArrayList<>();
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
        for (Object allPrincipal : allPrincipals) {
            User user = (User) allPrincipal;
            allUserNameList.add(user.getUsername());
        }

        //查询当前与系统交互的用户，存储在本地线程安全上下文，校验账号有效性
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        SysUserVo sysUserVo = sysUserService.findByLoginName(user.getUsername()).getData();

        //默认登陆成功
        String msg = "{\"code\":\"300\",\"msg\":\"登录成功\",\"url\":\"/index\"}";
        boolean flag = false;

        //登陆IP不在白名单
        String ipAddr = IpUtil.getIpAddr(httpServletRequest);
        String limitedIp = sysUserVo.getLimitedIp();
        if(!StringUtils.isEmpty(limitedIp) && !Arrays.asList(limitedIp.split(",")).contains(ipAddr)){
            msg = "{\"code\":\"400\",\"msg\":\"登陆IP不在白名单，请联系管理员\"}";
            flag = true;
        }

        //禁止多人在线
        if("N".equals(sysUserVo.getLimitMultiLogin()) && allUserNameList.contains(sysUserVo.getLoginName())){
            msg = "{\"code\":\"400\",\"msg\":\"该账号禁止多人在线，请联系管理员\"}";
            flag = true;
        }

        //超出有效时间
        if(!StringUtils.isEmpty(sysUserVo.getExpiredTime()) && new Date().getTime() > sysUserVo.getExpiredTime().getTime()){
            msg = "{\"code\":\"400\",\"msg\":\"该账号已失效，请联系管理员\"}";
            flag = true;
        }

        //禁止登陆系统
        if("N".equals(sysUserVo.getValid())){
            msg = "{\"code\":\"400\",\"msg\":\"该账号已被禁止登陆系统，请联系管理员\"}";
            flag = true;
        }

        if(flag){
            //校验不通过，清除当前的上下文
            SecurityContextHolder.clearContext();
        }else{
            //校验通过，注册session
            sessionRegistry.registerNewSession(httpServletRequest.getSession().getId(),user);
        }

        //加密

        //前端公钥
        String publicKey = httpServletRequest.getParameter("publicKey");

        log.info("前端公钥：" + publicKey);

        //jackson
        ObjectMapper mapper = new ObjectMapper();
        //jackson 序列化和反序列化 date处理
        mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        try {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            //每次响应之前随机获取AES的key，加密data数据
            String key = AesUtil.getKey();
            log.info("AES的key：" + key);
            log.info("需要加密的data数据：" + msg);
            String data = AesUtil.encrypt(msg, key);

            //用前端的公钥来解密AES的key，并转成Base64
            String aesKey = Base64.encodeBase64String(RsaUtil.encryptByPublicKey(key.getBytes(), publicKey));

            //转json字符串并转成Object对象，设置到Result中并赋值给返回值o
            httpServletResponse.setCharacterEncoding("UTF-8");
            httpServletResponse.setContentType("application/json; charset=utf-8");
            PrintWriter out = httpServletResponse.getWriter();
            out.print("{\"data\":{\"data\":\"" + data + "\",\"aesKey\":\"" + aesKey + "\"}}");
            out.flush();
            out.close();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
