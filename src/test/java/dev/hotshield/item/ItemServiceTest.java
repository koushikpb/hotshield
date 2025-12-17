package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.hotshield.HotShieldProperties;
import dev.hotshield.HotShieldProperties.Mode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ItemServiceTest {

    ItemRepository repository = mock(ItemRepository.class);
    Cache<Long, Item> cache = Caffeine.newBuilder().build();

    ItemService service(Mode mode) {
        var properties = new HotShieldProperties(mode, "node-a", List.of(), new HotShieldProperties.Cache(100, Duration.ofMinutes(1)));
        return new ItemService(repository, cache, properties);
    }

    @Test
    void directModeReadsTheDatabaseEveryTime() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.DIRECT);

        service.get(1);
        service.get(1);

        verify(repository, times(2)).findById(1);
    }

    @Test
    void cacheModeServesRepeatReadsFromCache() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.CACHE);

        var first = service.get(1);
        var second = service.get(1);

        assertThat(second).isEqualTo(first);
        verify(repository, times(1)).findById(1);
    }

    @Test
    void cacheModeDoesNotCacheMissingItems() {
        when(repository.findById(999)).thenReturn(Optional.empty());
        var service = service(Mode.CACHE);

        service.get(999);
        service.get(999);

        verify(repository, times(2)).findById(999);
    }

    @Test
    void updateInvalidatesTheCachedItem() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.CACHE);
        service.get(1);

        service.update(1, new ItemUpdate("new", 1, 1));

        assertThat(cache.getIfPresent(1L)).isNull();
    }
}
