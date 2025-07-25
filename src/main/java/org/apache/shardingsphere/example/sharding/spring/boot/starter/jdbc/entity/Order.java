package org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.entity;

import java.io.Serializable;

public class Order implements Serializable {
    
    private static final long serialVersionUID = 8306802022239174861L;
    
    private long orderId;

    private int orderType;
    
    private int userId;
    
    private long addressId;
    
    private String status;
    
    public long getOrderId() {
        return orderId;
    }
    
    public void setOrderId(final long orderId) {
        this.orderId = orderId;
    }

    public int getOrderType() {
        return orderType;
    }

    public void setOrderType(final int orderType) {
        this.orderType = orderType;
    }
    
    public int getUserId() {
        return userId;
    }
    
    public void setUserId(final int userId) {
        this.userId = userId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(final String status) {
        this.status = status;
    }
    
    public long getAddressId() {
        return addressId;
    }
    
    public void setAddressId(final long addressId) {
        this.addressId = addressId;
    }
    
    @Override
    public String toString() {
        return String.format("order_id: %s, order_type: %s, user_id: %s, address_id: %s, status: %s", orderId, orderType, userId, addressId, status);
    }
}
