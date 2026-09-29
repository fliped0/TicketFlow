package com.ticketflow.service;
import java.util.Locale;
import com.ticketflow.common.exception.BusinessException;
public final class AccountRules {
 private AccountRules() {}
 public static String username(String value) {
  if(value==null || !value.matches("[A-Za-z0-9_]{4,32}")) throw new BusinessException(400,"VALIDATION_ERROR","账号须为4至32位字母、数字或下划线");
  return value.toLowerCase(Locale.ROOT);
 }
 public static void password(String value) {
  int length=value==null?0:value.codePointCount(0,value.length());
  if(length<8 || length>64) throw new BusinessException(400,"VALIDATION_ERROR","密码须为8至64个字符");
 }
}
