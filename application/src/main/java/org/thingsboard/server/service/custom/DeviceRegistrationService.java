/**
 * Copyright © 2016-2025 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.device.DeviceDao;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.exception.DatabaseException;
import org.thingsboard.server.dao.exception.DeviceCredentialsValidationException;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.dao.model.sql.CustomerEntity;
import org.thingsboard.server.dao.model.sql.DeviceEntity;
import org.thingsboard.server.dao.model.sql.UserEntity;
import org.thingsboard.server.dao.sql.user.UserRepository;
import org.thingsboard.server.dao.user.UserDao;
import org.thingsboard.server.dao.user.UserService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class DeviceRegistrationService {

    private static final long TOKEN_EXPIRY_SECONDS = 3600;
    private static final String MAC_ID = "Mac_id";

    @Value("${COMMON_DASHBOARD_NAME}")
    private String COMMON_DASHBOARD_NAME;
    @Value("${HOME_DASHBOARD_NAME}")
    private String HOME_DASHBOARD_NAME;
    private final RestTemplate restTemplate;
    private final AttributesDao attributesDao;
    private final CustomerDao customerDao;
    private final DeviceDao deviceDao;
    private final UserDao userDao;
    private final UserRepository userRepository;
    @Value("${thingsboard.api.base-url}")
    private String baseUrl;
    @Value("${thingsboard.api.username}")
    private String username;
    @Value("${thingsboard.api.password}")
    private String password;
    private String cachedToken;
    private Instant tokenExpiryTime;

    @Autowired
    public DeviceRegistrationService(RestTemplate restTemplate, AttributesDao attributesDao, CustomerDao customerDao, DeviceDao deviceDao, UserDao userDao, UserRepository userRepository) {
        this.restTemplate = restTemplate;
        this.attributesDao = attributesDao;
        this.customerDao = customerDao;
        this.deviceDao = deviceDao;
        this.userDao = userDao;
        this.userRepository = userRepository;
    }

    public static String getStartDate() {
        LocalDate today = LocalDate.now();  // Get today's date
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd"); // Define format
        return today.format(formatter); // Return formatted date
    }

    // Optional: Get start date with custom offset (e.g., backdated or future)
    public static String getStartDateWithOffset(int daysOffset) {
        LocalDate date = LocalDate.now().plusDays(daysOffset);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        return date.format(formatter);
    }

    // Thread-safe token getter with caching
    private synchronized String loginAndGetToken() {
        if (cachedToken != null && tokenExpiryTime != null && Instant.now().isBefore(tokenExpiryTime)) {
            return cachedToken;
        }
        return fetchNewToken();
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAndGetToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // Separate method for fetching token from ThingsBoard
    private String fetchNewToken() {
        String loginUrl = baseUrl + "/api/auth/login";

        JSONObject loginJson = new JSONObject();
        loginJson.put("username", username);
        loginJson.put("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(loginJson.toString(), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(loginUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject jsonResponse = new JSONObject(response.getBody());
                cachedToken = jsonResponse.getString("token");
                tokenExpiryTime = Instant.now().plusSeconds(TOKEN_EXPIRY_SECONDS - 30);
                return cachedToken;
            } else {
                throw new RuntimeException("Login failed with status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception during login: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public UUID findDeviceByMacAndType(String macId, String deviceType) {
        try {
            Optional<UUID> deviceUUID = attributesDao.findDeviceIdByMacId(MAC_ID, macId, deviceType);
            if (deviceUUID.isPresent()) {
                return deviceUUID.get();
            }
        } catch (Exception e) {
            throw new DataValidationException("Failed to find device by MAC: " + e.getMessage(), e);
        }
        return null;
    }

    public Device findDeviceById(String deviceId) {
        try {
            Optional<DeviceEntity> deviceOptional = deviceDao.findDeviceById(deviceId);
            if (deviceOptional.isPresent()) {
                return deviceOptional.get().toData();
            }
        } catch (Exception e) {
            throw new DataValidationException("Failed to find device by Device Id: " + e.getMessage(), e);
        }
        return null;
    }

    public Customer findCustomerByEmail(String email) {
        try {
            CustomerEntity customer = customerDao.findCustomerByEmail(email);
            if (customer != null) {
                return customer.toData();
            }
        } catch (Exception e) {
            throw new DataValidationException("Failed to find Customer by Email: " + e.getMessage(), e);
        }
        return null;
    }

    public Customer findCustomerById(String id) {
        try {
            CustomerEntity customer = customerDao.findCustomerById(id);
            if (customer != null) {
                return customer.toData();
            }
        } catch (Exception e) {
            throw new DataValidationException("Failed to find Customer by Id: " + e.getMessage(), e);
        }
        return null;
    }

    public Customer createCustomer(String email) {
        HttpHeaders headers = getHeaders();

        Map<String, Object> customerRequest = new HashMap<>();
        customerRequest.put("title", email);
        customerRequest.put("email", email);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(customerRequest, headers);

        try {
            String createCustomerUrl = baseUrl + "/api/customer";
            ResponseEntity<Map> response = restTemplate.exchange(createCustomerUrl, HttpMethod.POST, entity, Map.class);

            Map<String, Object> body = response.getBody();
            Map<String, Object> idMap = (Map<String, Object>) body.get("id");

            Customer customer = new Customer();
            customer.setId(new CustomerId(UUID.fromString((String) idMap.get("id"))));
            customer.setTitle((String) body.get("title"));
            customer.setEmail((String) body.get("email"));

            String dashboardId = getDashboardIdByTitle(COMMON_DASHBOARD_NAME);
            String assignStatus = assignDashboardToCustomer(customer.getId().toString(), dashboardId);

            return customer;

        } catch (Exception e) {
            throw new DatabaseException("Failed to create customer: " + e.getMessage(), e);
        }
    }


    public String assignDeviceToCustomer(String deviceId, String customerId, String desiredDeviceName, String userEmail, boolean createUser) {
        try {
            // Step 1: Rename the device to a unique name under the customer BEFORE assignment
            renameDevice(deviceId, desiredDeviceName, customerId);
            log.info("Device renamed to '{}'", desiredDeviceName);

            // Step 2: Assign the device to the customer
            String assignUrl = baseUrl + "/api/customer/" + customerId + "/device/" + deviceId;
            HttpEntity<Void> assignEntity = new HttpEntity<>(getHeaders());
            restTemplate.exchange(assignUrl, HttpMethod.POST, assignEntity, Void.class);
            log.info("Device '{}' assigned to customer '{}'.", deviceId, customerId);

            String expiryDate = getStartDateWithOffset(60);
            String createdDate = getStartDate();

            if (createUser) {
                String input = createUserForCustomer(deviceId, customerId, desiredDeviceName, userEmail);
                String id = null;
                if (input != null && input.startsWith("userId::")) {
                    id = input.substring("userId::".length());
                    saveHomeDashboard(id);
                }
            }

            addServerAttributesConditionally(deviceId, expiryDate, createdDate, true);
            updateExpiryDateForOtherDevices(customerId, deviceId, expiryDate);

            return "Device assigned successfully.";

        } catch (HttpClientErrorException.Conflict e) {
            log.warn("User with email '{}' already exists.", userEmail);
            return "Device assigned, but user already exists.";
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new IncorrectParameterException("Error during assignment: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error: " + e.getMessage(), e);
        }
    }

    public String createUserForCustomer(String deviceId, String customerId, String deviceName, String userEmail) {
        try {
            String userUrl = baseUrl + "/api/user";
            Map<String, Object> userPayload = new HashMap<>();
            userPayload.put("email", userEmail);
            userPayload.put("authority", "CUSTOMER_USER");
            userPayload.put("customerId", Map.of("entityType", "CUSTOMER", "id", customerId));

            HttpEntity<Map<String, Object>> userRequest = new HttpEntity<>(userPayload, getHeaders());
            ResponseEntity<Map> userResponse = restTemplate.postForEntity(userUrl, userRequest, Map.class);

            if (userResponse.getStatusCode().is2xxSuccessful()) {
                log.info("User '{}' created successfully.", userEmail);
                Map<String, Object> body = userResponse.getBody();
                Map<String, Object> idMap = (Map<String, Object>) body.get("id");
                String userId = (String) idMap.get("id");
                return "userId::" + userId;
            } else {
                return "Device assigned, but user creation failed.";
            }

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void renameDevice(String deviceId, String newName, String customerId) {
        // 1) Get the existing device
        String getUrl = baseUrl + "/api/device/" + deviceId;
        Device device = restTemplate.exchange(
                getUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders()),
                Device.class
        ).getBody();

        if (device == null) {
            throw new IncorrectParameterException("Device not found: " + deviceId);
        }

        // 2) Generate a unique name under the same customer
        String finalName = newName;
        int counter = 1;
        while (isDeviceNameExistsUnderCustomer(customerId, finalName)) {
            finalName = newName + "_" + counter++;
        }

        // 3) Rename and update the device
        device.setLabel(finalName);

        String saveUrl = baseUrl + "/api/device";
        restTemplate.exchange(
                saveUrl,
                HttpMethod.POST,
                new HttpEntity<>(device, getHeaders()),
                Void.class
        );
    }

    private boolean isDeviceNameExistsUnderCustomer(String customerId, String deviceName) {
        String url = baseUrl + "/api/customer/" + customerId + "/devices?pageSize=100&page=0";
        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders()),
                String.class
        );
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            return false;
        }
        JSONObject json = new JSONObject(response.getBody());
        if (!json.has("data")) return false;
        JSONArray dataArray = json.getJSONArray("data");
        for (int i = 0; i < dataArray.length(); i++) {
            JSONObject deviceObj = dataArray.getJSONObject(i);
            String existingName = deviceObj.optString("label", "");
            if (existingName.equalsIgnoreCase(deviceName)) {
                return true;
            }
        }
        return false;
    }


    public String getDeviceAccessToken(String deviceId) {
        String url = baseUrl + "/api/device/" + deviceId + "/credentials";
        HttpEntity<Void> entity = new HttpEntity<>(getHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();

            if (body != null && body.containsKey("credentialsId")) {
                return (String) body.get("credentialsId");
            } else {
                throw new DeviceCredentialsValidationException("Access token not found in response for device: " + deviceId);
            }
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new DeviceCredentialsValidationException("Error fetching device token: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error while fetching device token: " + e.getMessage(), e);
        }
    }

    public void addServerAttributesConditionally(String deviceId,
                                                 String expiryDate, String createdDate,
                                                 boolean shouldUpdateCreatedDate) {
        String url = baseUrl + "/api/plugins/telemetry/DEVICE/" + deviceId + "/attributes/SERVER_SCOPE";

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("expiryDate", expiryDate);
        attributes.put("createdDate", createdDate); // Always include both now

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(attributes, getHeaders());

        try {
            restTemplate.postForEntity(url, request, Void.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update attributes for device " + deviceId + ": " + e.getMessage());
        }
    }

    public void updateExpiryDateForOtherDevices(String customerId, String excludedDeviceId, String expiryDate) {
        String url = baseUrl + "/api/customer/" + customerId + "/devices?pageSize=1000&page=0";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders()), Map.class);
            List<Map<String, Object>> devices = (List<Map<String, Object>>) response.getBody().get("data");

            for (Map<String, Object> device : devices) {
                String deviceId = ((Map<String, Object>) device.get("id")).get("id").toString();
                if (!deviceId.equals(excludedDeviceId)) {
                    Map<String, Object> attributes = new HashMap<>();
                    attributes.put("expiryDate", expiryDate);

                    String deviceAttrUrl = baseUrl + "/api/plugins/telemetry/DEVICE/" + deviceId + "/attributes/SERVER_SCOPE";
                    HttpEntity<Map<String, Object>> request = new HttpEntity<>(attributes, getHeaders());

                    restTemplate.postForEntity(deviceAttrUrl, request, Void.class);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update other devices: " + e.getMessage());
        }
    }

    public String unassignCustomerFromDevice(String deviceId) {
        String url = baseUrl + "/api/customer/device/" + deviceId;
        HttpEntity<String> entity = new HttpEntity<>(getHeaders());
        ResponseEntity<Void> response = restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                entity,
                Void.class
        );
        if (response.getStatusCode() == HttpStatus.OK) {
            return ("Device unassigned from customer successfully.");
        } else {
            return ("Failed to unassign device. Status: " + response.getStatusCode());
        }
    }

    public String getDashboardIdByTitle(String title) throws Exception {

        HttpEntity<Void> entity = new HttpEntity<>(getHeaders());
        String url = baseUrl + "/api/tenant/dashboards?pageSize=100&page=0";

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        ObjectMapper mapper = new ObjectMapper();
        if (response.getStatusCode() == HttpStatus.OK) {
            JsonNode data = mapper.readTree(response.getBody()).path("data");

            for (JsonNode dashboard : data) {
                String currentTitle = dashboard.path("title").asText();
                if (title.equals(currentTitle)) {
                    return dashboard.path("id").path("id").asText(); // UUID string
                }
            }
        }
        throw new RuntimeException("Dashboard id was not found");
    }

    public String assignDashboardToCustomer(String customerId, String dashboardId) {
        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<Void> entity = new HttpEntity<>(getHeaders());

        String assignUrl = baseUrl + "/api/customer/" + customerId + "/dashboard/" + dashboardId;

        ResponseEntity<String> response = restTemplate.exchange(assignUrl, HttpMethod.POST, entity, String.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            return ("Dashboard assigned successfully.");
        } else {
            throw new RuntimeException("Failed to assign dashboard. Status: " + response.getStatusCode());
        }
    }

    public String saveHomeDashboard(String userId) {
        try {
            UserEntity userEntity = userDao.findByUserIdValue(userId);
            User user = new User(userEntity.toData());

            // Convert existing additionalInfo (JsonNode) to JSONObject
            JSONObject additionalInfo;
            JsonNode existingInfo = user.getAdditionalInfo();

            if (existingInfo != null && !existingInfo.isNull()) {
                additionalInfo = new JSONObject(existingInfo.toString());
            } else {
                additionalInfo = new JSONObject();
            }

            // Put values into JSONObject
            String homeDashboardId = getDashboardIdByTitle(HOME_DASHBOARD_NAME);

            additionalInfo.put("homeDashboardId", homeDashboardId);
            additionalInfo.put("homeDashboardHideToolbar", false);

            // Convert back to JsonNode and set
            JsonNode updatedInfo = new ObjectMapper().readTree(additionalInfo.toString());
            user.setAdditionalInfo(updatedInfo);

            UserEntity userEntity1 = new UserEntity(user);
            userRepository.save(userEntity1);

            return ("Home Dashboard assigned successfully.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to assign dashboard.");
        }
    }
}
