package com.miaoshaproject.contorller;

import com.alibaba.druid.util.StringUtils;
import com.miaoshaproject.contorller.viewobject.UserVo;
import com.miaoshaproject.error.BussinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.UserService;
import com.miaoshaproject.service.model.UserModel;
import org.apache.tomcat.util.security.MD5Encoder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import sun.awt.EmbeddedFrame;
import sun.misc.BASE64Encoder;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
@CrossOrigin(origins = {"*"}, allowCredentials = "true")
//@CrossOrigin(allowCredentials = "true",allowedHeaders = "*")
public class UserController extends BaseController {


    @Autowired
    private UserService userService;

    //单例 内部threadLocal
    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    //用户登陆接口
    @RequestMapping(value = "/login", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType login(@RequestParam(name="telphone")String telphong,
                                  @RequestParam(name="password")String password) throws BussinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //参数校验
        if(org.apache.commons.lang3.StringUtils.isEmpty(telphong)|| org.apache.commons.lang3.StringUtils.isEmpty(password)){
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
        }
        //用户登陆服务，用来校验用户登陆是否合法
        UserModel userModel =userService.validateLogin(telphong,this.enCodeByMD5(password));

        //将登陆凭证加入到用户登陆成功的session中
        UserVo userVo = convertFromMode(userModel);
//        this.httpServletRequest.getSession().setAttribute("IS_LOGIN",true);
//        this.httpServletRequest.getSession().setAttribute("LOGIN_USER",userModel);
//        System.out.println(this.httpServletRequest.getSession().getAttribute("IS_LOGIN"));
        //        return CommonReturnType.create(null);
        //修改位登录用户登录验证成功后将对应的登录信息和登录凭证一起存入redis
        String uuidToken = UUID.randomUUID().toString();
        uuidToken = uuidToken.replace("-","");
        //建立token和用户登录态之间的联系
        redisTemplate.opsForValue().set(uuidToken,userModel); //只要redis中存在uuidtoken就是登录成功
        //设置超时时间  1个小时
        redisTemplate.expire(uuidToken,1,TimeUnit.HOURS);
        //对前端使用登录状态的地方也对应修改
        return CommonReturnType.create(uuidToken);
    }


    //用户注册接口
    @Transactional
    @RequestMapping(value = "/register", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType register(@RequestParam(name = "telphone") String telphone,
                                     @RequestParam(name = "otpCode") String otpCode,
                                     @RequestParam(name = "name") String name,
                                     @RequestParam(name = "gender") Integer gender,
                                     @RequestParam(name = "age") Integer age,
                                     @RequestParam(name="password")String password) throws BussinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //验证手机号和对应otpcode相符
        String inSessionOtpCode = (String) this.httpServletRequest.getSession().getAttribute(telphone);
        if (!StringUtils.equals(otpCode, inSessionOtpCode)) {
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "短信验证码不符合");
        }
        //用户注册流程
        UserModel userModel = new UserModel();
        userModel.setName(name);
        userModel.setGender(new Byte(String.valueOf(gender.intValue())));
        userModel.setAge(age);
        userModel.setTelphone(telphone);
        userModel.setRegisterMode("byphone");
        userModel.setEncrptPassword(this.enCodeByMD5(password));

        userService.register(userModel);
        return CommonReturnType.create(null);

    }


    public String enCodeByMD5(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        // 确定计算方法
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        BASE64Encoder base64Encoder = new BASE64Encoder();
        // 加密字符串
        String newStr = base64Encoder.encode(md5.digest(str.getBytes("utf-8")));
        System.out.println("加密后的密码为："+newStr);
        return newStr;
    }

    //用户获取otp短信接口
    @RequestMapping(value = "/getotp", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType getOtp(@RequestParam(name = "telphone") String telphone) {
        //按照一定规则生成OTP验证码
        Random random = new Random();
        int randomInt = random.nextInt(99999);
        randomInt += 10000;
        String otpCode = String.valueOf(randomInt);

        //将OTP验证同对应用户的手机号关联，使用HTTP session的方式绑定(redis非常适用）
        httpServletRequest.getSession().setAttribute(telphone, otpCode);

        //将OTP验证码通过短信通道发送给用户，省略
        System.out.println("telphone = " + telphone + "&optCode=" + otpCode);
        return CommonReturnType.create(null);
    }

    @RequestMapping("/get")
    public CommonReturnType getUser(@RequestParam(name = "id") Integer id) throws BussinessException {
        //调用service服务获取对应id对象返回给前端
        UserModel userModel = userService.getUserById(id);
        //若获取的对应用户信息存在
        if (userModel == null) {
//            userModel.setEncrptPassword("123");
            throw new BussinessException(EmBusinessError.USER_NOT_EXIST);
        }

        //将核心领域模型用户对象转换为可供UI使用的viewobject,领域模型中包含了敏感信息，只是用来做业务逻辑处理的，不能一股脑的全部返回给前端，不然会遭受到攻击
        UserVo userVo = convertFromMode(userModel);
        return CommonReturnType.create(userVo);
    }

    private UserVo convertFromMode(UserModel userModel) {
        if (userModel == null) {
            return null;
        }
        UserVo userVo = new UserVo();
        BeanUtils.copyProperties(userModel, userVo);
        return userVo;
    }
    //定义exceptionhandler解决未被controller吸收的exception   但ExceptionHandler结果只能返回路径
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public  Object handlerException(HttpServletRequest request,Exception ex){

        BussinessException bussinessException = (BussinessException)ex;
//        CommonReturnType commonReturnType = new CommonReturnType();
//        commonReturnType.setStatus("fail");
        Map<String,Object> responseData = new HashMap<>();
        responseData.put("errCode",bussinessException.getErrCode());
        responseData.put("errMsg",bussinessException.getErrMsg());
//        commonReturnType.setData(ex);
//        commonReturnType.setData(responseData);
    return  CommonReturnType.create(responseData,"fail");
    }

}
