package io.roadmoment.passed;

import java.util.Locale;

/**
 * Well-known chains. A driver recognises these at a glance, so "방금 그거 뭐야" is rarely about one: they rank
 * lower and an unfamiliar local shop next to them comes first. Matched on the start of the provider's place name
 * ("스타벅스 연남점", "GS25 동교점").
 */
final class Chains {
    private Chains() {}

    private static final String[] NAMES = {
        // cafés, bakeries, desserts
        "스타벅스", "투썸플레이스", "이디야", "메가mgc커피", "메가커피", "컴포즈커피", "빽다방", "할리스", "커피빈", "폴바셋",
        "파스쿠찌", "엔제리너스", "탐앤탐스", "더벤티", "매머드커피", "공차", "파리바게뜨", "뚜레쥬르", "던킨", "배스킨라빈스", "설빙",
        // fast food, chicken, pizza, casual dining
        "맥도날드", "버거킹", "롯데리아", "맘스터치", "kfc", "서브웨이", "교촌치킨", "bbq", "bhc", "굽네치킨", "네네치킨", "처갓집",
        "도미노피자", "피자헛", "미스터피자", "김밥천국", "본죽", "홍콩반점", "새마을식당", "한신포차", "역전할머니맥주", "아웃백", "빕스",
        // shops
        "gs25", "cu", "세븐일레븐", "이마트24", "미니스톱", "올리브영", "다이소",
    };
    /** Kinds where the driver is asking about a chain anyway ("방금 편의점 어디였지"). */
    private static final String[] CHAIN_KINDS = { "편의점", "햄버거", "마트", "약국", "주유소" };

    static boolean is(Place p) {
        String n = Hints.compact(p.name).toLowerCase(Locale.ROOT);
        for (String c : NAMES) if (n.startsWith(c)) return true;
        return false;
    }

    /** Whether to rank chains lower for this request: not when the driver named the brand or a chain-heavy kind. */
    static boolean demote(Hints h, Place p) {
        if (Hints.mentions(h.compact, p)) return false;
        for (String k : CHAIN_KINDS) if (h.kinds.contains(k)) return false;
        return is(p);
    }
}
