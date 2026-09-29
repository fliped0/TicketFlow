package com.ticketflow.service;
import org.junit.jupiter.api.Test;
import com.ticketflow.common.exception.BusinessException;
import static org.junit.jupiter.api.Assertions.*;
class AccountRulesTest {
 @Test void normalizesAsciiOnlyAndRejectsInvalidNames() {
  assertEquals("ticket_user",AccountRules.username("Ticket_USER"));
  for(String value:new String[]{"abc"," abcd","用户名名","a".repeat(33),"a-bc"})assertThrows(BusinessException.class,()->AccountRules.username(value));
 }
 @Test void passwordUsesCodePointsAndDoesNotTrim() {
  assertDoesNotThrow(()->AccountRules.password(" ".repeat(8)));
  assertDoesNotThrow(()->AccountRules.password("😀".repeat(64)));
  assertThrows(BusinessException.class,()->AccountRules.password("😀".repeat(65)));
  assertThrows(BusinessException.class,()->AccountRules.password("1234567"));
  assertThrows(BusinessException.class,()->AccountRules.password(null));
 }
}
