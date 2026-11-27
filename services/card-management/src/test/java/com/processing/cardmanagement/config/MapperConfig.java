package com.processing.cardmanagement.config;

import com.processing.cardmanagement.mappers.CardPersistenceMapper;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Подключает в срез {@code @DataJpaTest} только {@link CardPersistenceMapper}.
 */
@Configuration
@ComponentScan(
    basePackages = "com.processing.cardmanagement.mappers",
    useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = CardPersistenceMapper.class
    )
)
public class MapperConfig {
}
