package io.agentfarm.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The API and the migration contracts use Jackson 2. Keep one MVC codec. */
@Configuration
@SuppressWarnings("removal")
public class JsonWebConfig implements WebMvcConfigurer {
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.addFirst(new MappingJackson2HttpMessageConverter());
  }
}
