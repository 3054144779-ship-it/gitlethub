package com.huahuo.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import po.User;

import java.time.LocalDateTime;

@Aspect
public class DateSaveAop {
    @Before("execution(* com.huahuo.mapper.*.insert*(..))")
    public void before(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        User user = (User) args[0];
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        user.setIsDeleted(0);
    }
}
