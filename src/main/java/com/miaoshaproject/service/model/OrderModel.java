package com.miaoshaproject.service.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户下单的交易模型
 */

@Data
public class OrderModel {
    //要用string  订单id
    private String id;

    private Integer userId;
    //购买商品的id
    private Integer itemId;
    //购买商品的单价
    private BigDecimal itemPrice;
    //购买商品的数量
    private Integer amount;
    //购买金额
    private BigDecimal orderPrice;

    //若非空，表示以秒杀方式下单
    private Integer promoId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getItemId() {
        return itemId;
    }

    public void setItemId(Integer itemId) {
        this.itemId = itemId;
    }

    public BigDecimal getItemPrice() {
        return itemPrice;
    }

    public void setItemPrice(BigDecimal itemPrice) {
        this.itemPrice = itemPrice;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public BigDecimal getOrderPrice() {
        return orderPrice;
    }

    public void setOrderPrice(BigDecimal orderPrice) {
        this.orderPrice = orderPrice;
    }

    public Integer getPromoId() {
        return promoId;
    }

    public void setPromoId(Integer promoId) {
        this.promoId = promoId;
    }
}
