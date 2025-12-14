package dev.hotshield.item;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ItemController.class)
class ItemControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ItemService items;

    @Test
    void returnsItem() throws Exception {
        when(items.get(1)).thenReturn(Optional.of(item(1)));

        mvc.perform(get("/items/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(2500))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void missingItemIs404() throws Exception {
        when(items.get(anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/items/999")).andExpect(status().isNotFound());
    }

    @Test
    void updateOfMissingItemIs404() throws Exception {
        when(items.update(anyLong(), any())).thenReturn(Optional.empty());

        mvc.perform(put("/items/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"priceCents\":1,\"stock\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryLimitIsCapped() throws Exception {
        when(items.findByCategory("kitchen", 0, 200)).thenReturn(List.of());

        mvc.perform(get("/items").param("category", "kitchen").param("limit", "10000"))
                .andExpect(status().isOk());

        verify(items).findByCategory("kitchen", 0, 200);
    }

    static Item item(long id) {
        return new Item(id, "SKU-" + id, "kettle", "kitchen", 2500, 10, 3, OffsetDateTime.now());
    }
}
