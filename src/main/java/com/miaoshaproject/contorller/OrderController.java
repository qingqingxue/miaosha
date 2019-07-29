package com.miaoshaproject.contorller;
import com.google.common.util.concurrent.RateLimiter;
import com.miaoshaproject.error.BussinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.mq.MqProducer;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.OrderService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.model.OrderModel;
import com.miaoshaproject.service.model.UserModel;
import com.miaoshaproject.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @Date: 2019/4/9
 */
@Controller("/order")
@RequestMapping("/order")
@CrossOrigin(origins = {"*"}, allowCredentials = "true")
public class OrderController extends BaseController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    private RateLimiter orderCreateRateLimiter;

    @PostConstruct
    public void init(){
        executorService = Executors.newFixedThreadPool(20);

        orderCreateRateLimiter = RateLimiter.create(300); //限制每秒的tps  一秒钟允许通过10个。之前在两台服务器上可以达到700，那么可以允许每台的亮为300

    }

    //生成验证码
    @RequestMapping(value = "/generateverifycode",method = {RequestMethod.GET,RequestMethod.POST})
    @ResponseBody
    public void generateverifycode(HttpServletResponse response) throws BussinessException, IOException {
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能生成验证码");
        }
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel == null){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能生成验证码");
        }

        Map<String,Object> map = CodeUtil.generateCodeAndPic();

        redisTemplate.opsForValue().set("verify_code_"+userModel.getId(),map.get("code"));
        redisTemplate.expire("verify_code_"+userModel.getId(),10,TimeUnit.MINUTES);

        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());


    }
    //生成秒杀令牌  在接受令牌之前，用户必须首先把验证码发给服务器
    @RequestMapping(value = "/ ", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name="itemId")Integer itemId,
                                        @RequestParam(name="promoId")Integer promoId,
                                          @RequestParam(name="verifyCode")String verifyCode) throws BussinessException {

        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }

        UserModel userModel= (UserModel)redisTemplate.opsForValue().get(token);
        if(userModel == null){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }

        //通过verifycode验证验证码的有效性
        String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_"+userModel.getId());
        if(StringUtils.isEmpty(redisVerifyCode)){
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法");
        }
        if(!redisVerifyCode.equalsIgnoreCase(verifyCode)){
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法，验证码错误");
        }

        //获取秒杀访问令牌
        String promoToken =promoService.generateSecondKillToken(promoId,itemId,userModel.getId());

        if(promoToken == null){
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"生成令牌失败");
        }
        return CommonReturnType.create(promoToken);
    }

    //封装下单请求
    @RequestMapping(value = "/createorder", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name="itemId")Integer itemId,
                                        @RequestParam(name="promoId",required = false)Integer promoId,
                                        @RequestParam(name="amount")Integer amount,
                                        @RequestParam(name="promoToken",required = false)String promoToken) throws BussinessException {

        if(!orderCreateRateLimiter.tryAcquire()){ //没有获取到limit
            throw new BussinessException(EmBusinessError.RATELIMIT);
        }
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        //再去完成对应的上下单事务型消息机制
        UserModel userModel= (UserModel)redisTemplate.opsForValue().get(token);
        if(userModel == null){
            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        //验证秒杀令牌是否正确
        if(promoId !=null){ //promoId不为空，则令牌必须要存在
            String inRedisPromoToken = (String)redisTemplate.opsForValue().get("promo_token_"+promoId+"userId_"+userModel.getId()+"itemId_"+itemId+promoId);
            if(inRedisPromoToken == null){
                throw  new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌不正确");
            }
            if(!org.apache.commons.lang3.StringUtils.equals(promoToken,inRedisPromoToken)){
                throw  new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌不正确");
            }

        }

        //同步调用线程池的submit方法
        //拥塞窗口为20的等待队列，用来队列化泄洪
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                String stockLogId = itemService.initStockLog(itemId,amount);

                if(!mqProducer.transactionAsyncReducerStock(userModel.getId(),itemId,promoId,amount,stockLogId)){
                    throw new BussinessException(EmBusinessError.UNKNOW_ERROR,"下单失败");
                };
                return null;
            }
        });
        //等待future对象执行完成
        try {
            future.get();
        } catch (InterruptedException e) {
            throw new BussinessException(EmBusinessError.UNKNOW_ERROR);
        } catch (ExecutionException e) {
            throw new BussinessException(EmBusinessError.UNKNOW_ERROR);
        }

        //获取登录信息（Boolean)
//        Boolean isLogin = (Boolean) this.httpServletRequest.getSession().getAttribute("IS_LOGIN");
//        if(isLogin == null || !isLogin.booleanValue()){
//            throw new BussinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
//        }
//        UserModel userModel = (UserModel) this.httpServletRequest.getSession().getAttribute("LOGIN_USER");
//        OrderModel orderModel = orderService.createOrder(userModel.getId(),itemId,promoId,amount);


        //判断库存是否已售罄，如果已经售罄，则直接返回下单失败
//        redisTemplate.opsForValue().get("promo_item_stock_invalid_"+itemId)
        //---------------------前置到令牌生成部分---------------
        //加入库存流水init状态
//        if(redisTemplate.hasKey("promo_item_invalid_"+itemId)){
//            throw new BussinessException(EmBusinessError.UNKNOW_ERROR,"库存不足");
//        }
        //---------------------前置到令牌生成部分--------------

        return CommonReturnType.create(null);

    }

}
