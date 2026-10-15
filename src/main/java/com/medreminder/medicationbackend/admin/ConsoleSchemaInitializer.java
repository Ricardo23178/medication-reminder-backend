package com.medreminder.medicationbackend.admin;

import javax.sql.DataSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Only adds console tables and missing seed keys. Disable after DBA-managed migration if needed. */
@Component
@ConditionalOnProperty(name="app.initialize-console", havingValue="true", matchIfMissing=true)
public class ConsoleSchemaInitializer implements CommandLineRunner {
    private final DataSource dataSource;
    public ConsoleSchemaInitializer(DataSource dataSource) { this.dataSource=dataSource; }
    @Override public void run(String... args) {
        var script=new ResourceDatabasePopulator(new ClassPathResource("db/admin-console.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.execute(dataSource);
    }
}
