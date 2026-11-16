package com.example.payment_system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "transfer.stream.enabled=false")
class PaymentSystemApplicationTests {

	@Test
	void contextLoads() {
	}

}
