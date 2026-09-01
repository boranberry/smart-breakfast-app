package com.smartoffice.breakfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the full post-order approval workflow end to end:
 * open room -> unpriced orders -> close -> receipt entry -> approval ->
 * verified menu available to the next room.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovalWorkflowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private static String adminToken;
    private static String userToken;
    private static Long roomId;
    private static Long restaurantId;

    private JsonNode call(MockHttpServletRequestBuilder builder, String token, Object body, int expectedStatus)
            throws Exception {
        builder.contentType(MediaType.APPLICATION_JSON);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.content(json.writeValueAsString(body));

        MvcResult result = mvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? null : json.readTree(content);
    }

    @Test
    @Order(1)
    void firstUserBecomesAdminAndSecondIsARegularUser() throws Exception {
        JsonNode admin = call(post("/api/auth/register"), null,
                java.util.Map.of("name", "Rania", "phone", "0100000001", "password", "secret123"), 201);
        assertThat(admin.get("role").asText()).isEqualTo("ADMIN");
        adminToken = admin.get("token").asText();

        JsonNode user = call(post("/api/auth/register"), null,
                java.util.Map.of("name", "Sami", "phone", "0100000002", "password", "secret123"), 201);
        assertThat(user.get("role").asText()).isEqualTo("USER");
        userToken = user.get("token").asText();
    }

    @Test
    @Order(2)
    void creatingARoomMaterialisesTheRestaurantWithAnEmptyMenu() throws Exception {
        JsonNode room = call(post("/api/rooms"), adminToken,
                java.util.Map.of("restaurantName", "Abu Ali", "restaurantPhone", "0221234567"), 201);

        roomId = room.get("id").asLong();
        restaurantId = room.get("restaurantId").asLong();

        assertThat(room.get("status").asText()).isEqualTo("OPEN");
        // No receipt has ever been approved for this restaurant yet.
        assertThat(room.get("menuItemCount").asInt()).isZero();

        JsonNode menu = call(get("/api/rooms/" + roomId + "/menu"), userToken, null, 200);
        assertThat(menu).isEmpty();
    }

    @Test
    @Order(3)
    void usersCanOrderWithoutKnowingPrices() throws Exception {
        // Requirement 1: prices need not be accurate or even present.
        call(post("/api/rooms/" + roomId + "/orders"), userToken,
                java.util.Map.of("itemName", "Falafel Sandwich", "quantity", 2), 201);
        call(post("/api/rooms/" + roomId + "/orders"), userToken,
                java.util.Map.of("itemName", "Tea", "quantity", 1), 201);
        // The admin guesses a price; it should be discarded at receipt time.
        call(post("/api/rooms/" + roomId + "/orders"), adminToken,
                java.util.Map.of("itemName", "Falafel Sandwich", "price", 99.0, "quantity", 1), 201);

        JsonNode summary = call(get("/api/rooms/" + roomId + "/orders/summary"), adminToken, null, 200);
        assertThat(summary.get("pricesVerified").asBoolean()).isFalse();
        assertThat(summary.get("participantCount").asInt()).isEqualTo(2);
    }

    @Test
    @Order(4)
    void regularUsersCannotReachTheAdminApprovalSurface() throws Exception {
        call(get("/api/admin/rooms/pending-approval"), userToken, null, 403);
        call(get("/api/admin/rooms/" + roomId + "/bill-preview"), userToken, null, 403);
        call(post("/api/admin/rooms/" + roomId + "/approve"), userToken,
                java.util.Map.of("items", java.util.List.of()), 403);
        call(post("/api/rooms/" + roomId + "/receipt"), userToken,
                java.util.Map.of("items", java.util.List.of(), "totalDelivery", 10.0), 403);
        // And unauthenticated callers get nothing at all. (403 rather than 401:
        // with no AuthenticationEntryPoint configured, Spring Security's default
        // for an anonymous denial is Http403ForbiddenEntryPoint.)
        call(get("/api/admin/rooms/pending-approval"), null, null, 403);
    }

    @Test
    @Order(5)
    void receiptCannotBeEnteredWhileTheRoomIsStillOpen() throws Exception {
        JsonNode err = call(post("/api/rooms/" + roomId + "/receipt"), adminToken,
                java.util.Map.of(
                        "items", java.util.List.of(java.util.Map.of("name", "Tea", "verifiedPrice", 1.0)),
                        "totalDelivery", 10.0), 400);
        assertThat(err.get("message").asText()).contains("Close the room");
    }

    @Test
    @Order(6)
    void enteringTheReceiptSplitsTheBillAndParksTheRoomForApproval() throws Exception {
        call(post("/api/rooms/" + roomId + "/close"), adminToken, null, 200);

        JsonNode draft = call(post("/api/rooms/" + roomId + "/receipt"), adminToken,
                java.util.Map.of(
                        "items", java.util.List.of(
                                java.util.Map.of("name", "Falafel Sandwich", "verifiedPrice", 4.50),
                                java.util.Map.of("name", "Tea", "verifiedPrice", 1.00)),
                        "totalDelivery", 9.00,
                        "receiptTotal", 23.50),
                200);

        assertThat(draft.get("status").asText()).isEqualTo("PENDING_ADMIN_APPROVAL");
        assertThat(draft.get("unpricedItems")).isEmpty();

        JsonNode bill = draft.get("bill");
        assertThat(bill.get("pricesVerified").asBoolean()).isTrue();
        // Food: Sami 2x4.50 + 1x1.00 = 10.00 ; Rania 1x4.50 = 4.50 -> 14.50
        // The admin's 99.0 guess was overwritten by the receipt price.
        assertThat(bill.get("totalFoodCost").asDouble()).isEqualTo(14.50);
        assertThat(bill.get("grandTotal").asDouble()).isEqualTo(23.50);
        assertThat(bill.get("deliverySharePerPerson").asDouble()).isEqualTo(4.50);
        // receiptTotal 23.50 - computed 23.50
        assertThat(draft.get("reconciliationDelta").asDouble()).isZero();

        JsonNode rania = bill.get("breakdown").get(0);
        assertThat(rania.get("userName").asText()).isEqualTo("Rania");
        assertThat(rania.get("finalTotal").asDouble()).isEqualTo(9.00);

        JsonNode sami = bill.get("breakdown").get(1);
        assertThat(sami.get("userName").asText()).isEqualTo("Sami");
        assertThat(sami.get("finalTotal").asDouble()).isEqualTo(14.50);

        // Still not on the menu: approval has not happened yet.
        assertThat(call(get("/api/restaurants/" + restaurantId + "/menu"), adminToken, null, 200)).isEmpty();

        JsonNode queue = call(get("/api/admin/rooms/pending-approval"), adminToken, null, 200);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("id").asLong()).isEqualTo(roomId);
    }

    @Test
    @Order(7)
    void approvalIsRefusedWhenAnOrderedItemHasNoReceiptPrice() throws Exception {
        JsonNode err = call(post("/api/admin/rooms/" + roomId + "/approve"), adminToken,
                java.util.Map.of("items", java.util.List.of(
                        java.util.Map.of("name", "Tea", "verifiedPrice", 1.00))),
                400);
        assertThat(err.get("message").asText()).contains("Falafel Sandwich");
    }

    @Test
    @Order(8)
    void approvingWritesTheVerifiedMenuAndClosesTheRoom() throws Exception {
        JsonNode approval = call(post("/api/admin/rooms/" + roomId + "/approve"), adminToken,
                java.util.Map.of("items", java.util.List.of(
                        // The admin corrects the falafel price at the approval screen.
                        java.util.Map.of("name", "Falafel Sandwich", "verifiedPrice", 5.00),
                        java.util.Map.of("name", "Tea", "verifiedPrice", 1.00))),
                200);

        assertThat(approval.get("status").asText()).isEqualTo("APPROVED_AND_CLOSED");
        assertThat(approval.get("approvedByName").asText()).isEqualTo("Rania");
        assertThat(approval.get("createdMenuItems")).hasSize(2);

        // Correction flowed through to the split: Sami 2x5.00 + 1.00 = 11.00.
        JsonNode bill = approval.get("bill");
        assertThat(bill.get("totalFoodCost").asDouble()).isEqualTo(16.00);
        assertThat(bill.get("breakdown").get(1).get("finalTotal").asDouble()).isEqualTo(15.50);

        JsonNode menu = call(get("/api/restaurants/" + restaurantId + "/menu"), adminToken, null, 200);
        assertThat(menu).hasSize(2);
        assertThat(menu.get(0).get("name").asText()).isEqualTo("Falafel Sandwich");
        assertThat(menu.get(0).get("verifiedPrice").asDouble()).isEqualTo(5.00);
    }

    @Test
    @Order(9)
    void anApprovedRoomIsFrozen() throws Exception {
        call(post("/api/admin/rooms/" + roomId + "/approve"), adminToken,
                java.util.Map.of("items", java.util.List.of(
                        java.util.Map.of("name", "Tea", "verifiedPrice", 1.00))), 400);

        call(post("/api/rooms/" + roomId + "/receipt"), adminToken,
                java.util.Map.of(
                        "items", java.util.List.of(java.util.Map.of("name", "Tea", "verifiedPrice", 2.00)),
                        "totalDelivery", 9.00), 400);

        call(post("/api/rooms/" + roomId + "/orders"), userToken,
                java.util.Map.of("itemName", "Coffee", "quantity", 1), 409);
    }

    @Test
    @Order(10)
    void aFutureRoomForTheSameRestaurantStartsWithVerifiedPrices() throws Exception {
        // Requirement 4: reopen for the same restaurant and the saved menu is there.
        JsonNode room2 = call(post("/api/rooms"), adminToken,
                java.util.Map.of("restaurantId", restaurantId, "restaurantName", "Abu Ali"), 201);
        Long room2Id = room2.get("id").asLong();

        assertThat(room2.get("menuItemCount").asInt()).isEqualTo(2);

        JsonNode menu = call(get("/api/rooms/" + room2Id + "/menu"), userToken, null, 200);
        assertThat(menu).hasSize(2);
        assertThat(menu.get(1).get("name").asText()).isEqualTo("Tea");
        assertThat(menu.get(1).get("verifiedPrice").asDouble()).isEqualTo(1.00);

        // Matching on name is case-insensitive, so "abu ali" is not a second restaurant.
        JsonNode room3 = call(post("/api/rooms"), adminToken,
                java.util.Map.of("restaurantName", "abu ali"), 201);
        assertThat(room3.get("restaurantId").asLong()).isEqualTo(restaurantId);
        assertThat(room3.get("menuItemCount").asInt()).isEqualTo(2);
    }

    @Test
    @Order(11)
    void reapprovingAnItemUpdatesRatherThanDuplicatesItsMenuLine() throws Exception {
        JsonNode room = call(post("/api/rooms"), adminToken,
                java.util.Map.of("restaurantId", restaurantId, "restaurantName", "Abu Ali"), 201);
        Long id = room.get("id").asLong();

        call(post("/api/rooms/" + id + "/orders"), userToken,
                java.util.Map.of("itemName", "Tea", "quantity", 1), 201);
        call(post("/api/rooms/" + id + "/close"), adminToken, null, 200);
        call(post("/api/rooms/" + id + "/receipt"), adminToken,
                java.util.Map.of(
                        "items", java.util.List.of(java.util.Map.of("name", "Tea", "verifiedPrice", 1.25)),
                        "totalDelivery", 5.00), 200);

        JsonNode approval = call(post("/api/admin/rooms/" + id + "/approve"), adminToken,
                java.util.Map.of("items", java.util.List.of(
                        java.util.Map.of("name", "Tea", "verifiedPrice", 1.25))), 200);

        assertThat(approval.get("createdMenuItems")).isEmpty();
        assertThat(approval.get("updatedMenuItems")).hasSize(1);

        JsonNode menu = call(get("/api/restaurants/" + restaurantId + "/menu"), adminToken, null, 200);
        assertThat(menu).hasSize(2); // still two lines, Tea was repriced not duplicated
        assertThat(menu.get(1).get("verifiedPrice").asDouble()).isEqualTo(1.25);
    }
}
