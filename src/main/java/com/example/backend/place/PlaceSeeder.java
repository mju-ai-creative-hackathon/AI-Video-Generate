package com.example.backend.place;

import com.example.backend.place.domain.Place;
import com.example.backend.place.domain.PlaceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.backend.place.domain.PlaceCategory.CAFE;
import static com.example.backend.place.domain.PlaceCategory.CULTURE;
import static com.example.backend.place.domain.PlaceCategory.EVENT;
import static com.example.backend.place.domain.PlaceCategory.RESTAURANT;
import static com.example.backend.place.domain.PlaceCategory.WALK;

/**
 * 장소 테이블이 비어 있을 때만 서울 샘플 장소를 넣는다.
 * 테스트에서는 app.seed.places.enabled=false 로 꺼 둔다.
 */
@Component
@ConditionalOnProperty(name = "app.seed.places.enabled", havingValue = "true", matchIfMissing = true)
public class PlaceSeeder implements ApplicationRunner {
    private final PlaceRepository placeRepository;

    public PlaceSeeder(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (placeRepository.count() > 0) {
            return;
        }
        placeRepository.saveAll(List.of(
                new Place("북촌 골목 산책", "한옥 지붕 사이로 이어지는 골목을 천천히 걸으며 서울의 옛 정취를 느껴 보세요.",
                        WALK, "종로구", "서울 종로구 계동길 37", image("bukchon"), 37.5826, 126.9837, true, 1),
                new Place("궁의 색을 만나는 오후", "경복궁과 국립고궁박물관에서 단청과 궁중 유물의 색을 감상하는 코스입니다.",
                        CULTURE, "종로구", "서울 종로구 사직로 161", image("gyeongbokgung"), 37.5796, 126.9770, true, 2),
                new Place("성수 브런치 투어", "붉은 벽돌 공장을 개조한 성수동 브런치 카페와 베이커리를 둘러봅니다.",
                        RESTAURANT, "성동구", "서울 성동구 연무장길 일대", image("seongsu-brunch"), 37.5446, 127.0557, true, 3),
                new Place("익선동 한옥 카페 골목", "좁은 한옥 골목에 숨은 감성 카페에서 여유로운 오후를 보내 보세요.",
                        CAFE, "종로구", "서울 종로구 수표로28길 일대", image("ikseon-cafe"), 37.5743, 126.9897, true, 4),
                new Place("한강 노을 피크닉", "여의도 한강공원에서 돗자리를 펴고 노을과 야경을 함께 즐기는 코스입니다.",
                        EVENT, "영등포구", "서울 영등포구 여의동로 330", image("hangang-sunset"), 37.5284, 126.9327, true, 5),
                new Place("을지로 노포 맛집 탐방", "오래된 인쇄 골목 사이 노포에서 서울의 오랜 맛을 만나 보세요.",
                        RESTAURANT, "중구", "서울 중구 을지로 일대", image("euljiro"), 37.5660, 126.9910, true, 6),
                new Place("연남동 루프탑 카페", "경의선숲길 옆 루프탑에서 커피 한 잔과 함께 동네 풍경을 내려다봅니다.",
                        CAFE, "마포구", "서울 마포구 연남동 일대", image("yeonnam-rooftop"), 37.5627, 126.9255, true, 7),
                new Place("DDP 디자인 전시 산책", "동대문디자인플라자의 곡선 건축과 기획 전시를 함께 즐길 수 있습니다.",
                        CULTURE, "중구", "서울 중구 을지로 281", image("ddp"), 37.5665, 127.0092, true, 8),
                new Place("서울숲 초록 산책", "도심 속 숲길과 사슴 우리, 꽃밭을 따라 걷는 가벼운 산책 코스입니다.",
                        WALK, "성동구", "서울 성동구 뚝섬로 273", image("seoul-forest"), 37.5444, 127.0374, true, 9),
                new Place("광장시장 먹거리 투어", "빈대떡, 마약김밥, 육회까지 광장시장의 대표 먹거리를 맛봅니다.",
                        RESTAURANT, "종로구", "서울 종로구 창경궁로 88", image("gwangjang-market"), 37.5700, 126.9996, false, 10),
                new Place("청계천 야간 산책", "조명이 켜진 청계천을 따라 걸으며 도심의 밤을 느껴 보세요.",
                        WALK, "중구", "서울 중구 청계천로 일대", image("cheonggyecheon"), 37.5694, 126.9780, false, 11),
                new Place("문래 창작촌 아트 마켓", "철공소와 작업실이 공존하는 문래동에서 열리는 주말 아트 마켓입니다.",
                        EVENT, "영등포구", "서울 영등포구 문래동 일대", image("mullae-art"), 37.5146, 126.8953, true, 12)
        ));
    }

    private static String image(String seed) {
        return "https://picsum.photos/seed/" + seed + "/800/600";
    }
}
