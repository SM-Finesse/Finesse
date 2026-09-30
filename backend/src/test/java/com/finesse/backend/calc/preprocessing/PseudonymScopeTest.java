package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.domain.PseudonymId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PseudonymScopeTest {

    private final MatchScopedPseudonymizer pseudonymizer = new MatchScopedPseudonymizer();
    private final NicknamePseudonymizer masker = new NicknamePseudonymizer();

    @Test
    void 같은_유저_ID는_같은_PseudonymId를_재사용하고_처음_본_순서대로_채번한다() {
        PseudonymScope scope = pseudonymizer.newScope();

        PseudonymId first = scope.resolve("uid-1", "alpha");
        PseudonymId second = scope.resolve("uid-2", "bravo");
        PseudonymId again = scope.resolve("uid-1", "alpha");

        assertThat(first.value()).isEqualTo("User_A");
        assertThat(second.value()).isEqualTo("User_B");
        assertThat(again).isEqualTo(first);
        assertThat(scope.size()).isEqualTo(2);
    }

    @Test
    void 개명한_상대는_같은_PseudonymId이고_표시_닉네임은_가장_최근_매치_당시_닉네임이다() {
        PseudonymScope scope = pseudonymizer.newScope();

        // 최신 매치부터 전달: 최근 닉네임 newname, 과거 닉네임 oldname
        PseudonymId recent = scope.resolve("uid-1", "newname");
        PseudonymId past = scope.resolve("uid-1", "oldname");

        assertThat(past).isEqualTo(recent);
        assertThat(scope.resolveMaskedNickname(recent, masker)).isEqualTo("ne**ame");
    }

    @Test
    void 다른_유저_ID가_같은_닉네임을_쓰면_서로_다른_PseudonymId다() {
        PseudonymScope scope = pseudonymizer.newScope();

        PseudonymId a = scope.resolve("uid-1", "samename");
        PseudonymId b = scope.resolve("uid-2", "samename");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 요청마다_새_스코프는_채번이_초기화된다() {
        PseudonymScope first = pseudonymizer.newScope();
        first.resolve("uid-1", "alpha");
        first.resolve("uid-2", "bravo");

        PseudonymScope second = pseudonymizer.newScope();

        assertThat(second.resolve("uid-9", "zulu").value()).isEqualTo("User_A");
    }

    @Test
    void 스코프에서_발급하지_않은_PseudonymId는_조회할_수_없다() {
        PseudonymScope scope = pseudonymizer.newScope();

        assertThatThrownBy(() -> scope.resolveMaskedNickname(PseudonymId.of(5), masker))
                .isInstanceOf(IllegalArgumentException.class);
    }
}