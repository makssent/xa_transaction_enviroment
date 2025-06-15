package org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc;

import org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.service.ExampleService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.sql.SQLException;

@SpringBootApplication
@Import(TransactionConfiguration.class)
public class ExampleMain {

    public static void main(final String[] args) throws SQLException{
        try (ConfigurableApplicationContext applicationContext = SpringApplication.run(ExampleMain.class, args)) {
            ExampleService exampleService = applicationContext.getBean(ExampleService.class);
            exampleService.run();
            System.exit(0);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
