package com.sudo.raillo.batch;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.train.domain.Train;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
@EntityScan(basePackageClasses = {Booking.class, Member.class, Order.class, Payment.class, Train.class})
public class RailloBatchApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(RailloBatchApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = application.run(args);
		System.exit(SpringApplication.exit(context));
	}

}
