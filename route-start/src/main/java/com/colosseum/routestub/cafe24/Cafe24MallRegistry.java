package com.colosseum.routestub.cafe24;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * mallId 별로 어느 소비 서비스가 연결했는지 기억한다(메모리, 재기동 시 사라짐). Cafe24 웹훅과 App URL 진입에는 service 가
 * 없고 mall_id 만 있어서, 라우터가 전달처를 정하려면 이 연결 기록이 필요하다. 라우터 콜백이 state 의 mallId, service 로 채운다.
 *
 * <p>정식에서는 영속 저장소가 이 자리를 대신한다. 한 몰이 두 서비스에 연결되면 웹훅을 둘 다에 보내야 하는데, 운영에서
 * 한 몰은 한 서비스에만 연결한다는 전제라면 여기서 두 번째 연결을 막거나 경고하게 된다.
 */
@Component
public class Cafe24MallRegistry {

    /** 기록이 없는 몰(라우터 재기동 등)의 웹훅을 보낼 기본 전달처. 스텁에서 콜백 앱이 떠 있는 두 서비스다. */
    static final List<String> ALL_SERVICES = List.of("cgs", "colo1.0");

    private final Map<String, Set<String>> services = new ConcurrentHashMap<>();

    void record(String mallId, String service) {
        if (mallId == null || service == null) {
            return;
        }
        services.computeIfAbsent(mallId, k -> ConcurrentHashMap.newKeySet()).add(service);
    }

    /** 이 몰에 연결한 서비스. 기록이 없으면 빈 집합. */
    Set<String> servicesOf(String mallId) {
        return mallId == null ? Set.of() : Set.copyOf(services.getOrDefault(mallId, Set.of()));
    }
}
