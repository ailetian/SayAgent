package com.hify.hify.conversation.web;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.conversation.dto.FeedbackAdminView;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.entity.Message;
import com.hify.hify.conversation.entity.MessageFeedback;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageFeedbackRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.conversation.service.MessageFeedbackService;
import com.hify.hify.user.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * K0808 T8 验收：
 * 上段 = 控制层（MockMvc standalone，不加载 Spring 上下文，仅验证路由/JSON 绑定/委派）；
 * 下段 = 服务层纯单测（手动 mock 仓储），验 upsert/取消删除/消息不存在/非法 rating/管理员 FORBIDDEN/我的反馈映射等业务规则。
 *
 * <p>说明：未登录 401 由共享 SecurityConfig 过滤链统一拦截（ConversationControllerTest 已覆盖 /api/chat/**），
 * 本测试通过手动注入 SecurityContext 验证「登录后」委派与业务规则。
 */
class MessageFeedbackApiTest {

    private MessageFeedbackService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(MessageFeedbackService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MessageFeedbackController(service)).build();
        // 模拟已登录用户（principal=tester，角色 USER），供控制器 currentUser() 读取。
        Authentication auth = new UsernamePasswordAuthenticationToken("tester", null,
                List.of(() -> "ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ============ 控制层（路由 / 绑定 / 委派） ============

    @Test
    void post_delegatesToService_withReason() throws Exception {
        mockMvc.perform(post("/api/chat/messages/1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"THUMBS_DOWN\",\"reason\":\"检索不准\"}"))
                .andExpect(status().isOk());

        verify(service).submit(eq("tester"), eq(1L), eq("THUMBS_DOWN"), eq("检索不准"));
    }

    @Test
    void post_cancel_delegates_withNullRating() throws Exception {
        mockMvc.perform(post("/api/chat/messages/1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":null}"))
                .andExpect(status().isOk());

        verify(service).submit(eq("tester"), eq(1L), isNull(), any());
    }

    @Test
    void getAdmin_delegates_withDefaultLimit() throws Exception {
        mockMvc.perform(get("/api/chat/feedback")).andExpect(status().isOk());
        verify(service).adminView(any(), any(), any(), eq(20));
    }

    @Test
    void getMine_delegates() throws Exception {
        mockMvc.perform(get("/api/chat/feedback/mine").param("messageIds", "1", "2"))
                .andExpect(status().isOk());
        verify(service).myRatings(eq("tester"), anyList());
    }

    // ============ 服务层纯单测（手动 mock 仓储） ============

    private MessageFeedbackService buildService(MessageFeedbackRepository repo,
                                                MessageRepository msgRepo,
                                                ConversationRepository convRepo,
                                                UserService userService) {
        return new MessageFeedbackService(repo, msgRepo, convRepo, userService);
    }

    @Test
    void submit_thumbsDown_callsUpsertWithAgentId() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        ConversationRepository convRepo = mock(ConversationRepository.class);
        UserService userService = mock(UserService.class);

        Message msg = new Message();
        msg.setConversationId("c-1");
        when(msgRepo.findById(1L)).thenReturn(Optional.of(msg));
        Conversation conv = new Conversation();
        conv.setAgentId("a-1");
        when(convRepo.findByConversationId("c-1")).thenReturn(Optional.of(conv));
        when(userService.resolveUserId("tester")).thenReturn(1L);

        buildService(repo, msgRepo, convRepo, userService)
                .submit("tester", 1L, "THUMBS_DOWN", "检索不准");

        verify(repo).upsert(1L, 1L, "a-1", null, "THUMBS_DOWN", "检索不准");
    }

    @Test
    void submit_toggleToUp_updatesSameRow() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        ConversationRepository convRepo = mock(ConversationRepository.class);
        UserService userService = mock(UserService.class);

        Message msg = new Message();
        msg.setConversationId("c-1");
        when(msgRepo.findById(1L)).thenReturn(Optional.of(msg));
        when(convRepo.findByConversationId("c-1")).thenReturn(Optional.of(new Conversation()));
        when(userService.resolveUserId("tester")).thenReturn(1L);

        MessageFeedbackService svc = buildService(repo, msgRepo, convRepo, userService);
        svc.submit("tester", 1L, "THUMBS_DOWN", "检索不准");
        svc.submit("tester", 1L, "THUMBS_UP", null);

        // 唯一键 (message_id, user_id) 生效：两次均走 upsert（覆盖写），仍 1 行。
        verify(repo, times(2)).upsert(eq(1L), eq(1L), any(), isNull(), any(), any());
    }

    @Test
    void submit_nullRating_deletesRow() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        ConversationRepository convRepo = mock(ConversationRepository.class);
        UserService userService = mock(UserService.class);

        Message msg = new Message();
        msg.setConversationId("c-1");
        when(msgRepo.findById(1L)).thenReturn(Optional.of(msg));
        when(userService.resolveUserId("tester")).thenReturn(1L);

        buildService(repo, msgRepo, convRepo, userService).submit("tester", 1L, null, null);

        verify(repo).deleteByMessageIdAndUserId(1L, 1L);
        verify(repo, org.mockito.Mockito.never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submit_messageNotFound_throwsResourceNotFound() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        UserService userService = mock(UserService.class);
        when(msgRepo.findById(99L)).thenReturn(Optional.empty());
        when(userService.resolveUserId("tester")).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> buildService(repo, msgRepo, mock(ConversationRepository.class), userService)
                        .submit("tester", 99L, "THUMBS_DOWN", null));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void submit_invalidRating_throwsParamInvalid() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        UserService userService = mock(UserService.class);
        Message msg = new Message();
        msg.setConversationId("c-1");
        when(msgRepo.findById(1L)).thenReturn(Optional.of(msg));
        when(userService.resolveUserId("tester")).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> buildService(repo, msgRepo, mock(ConversationRepository.class), userService)
                        .submit("tester", 1L, "BAD_RATING", null));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void adminView_nonAdmin_throwsForbidden() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        UserService userService = mock(UserService.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenAnswer(inv -> List.<GrantedAuthority>of(() -> "ROLE_USER"));

        BizException ex = assertThrows(BizException.class,
                () -> buildService(repo, mock(MessageRepository.class),
                                mock(ConversationRepository.class), userService)
                        .adminView(auth, null, null, 20));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void adminView_admin_returnsTopNandReasons() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        UserService userService = mock(UserService.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenAnswer(inv -> List.<GrantedAuthority>of(() -> "ROLE_ADMIN"));
        when(repo.topThumbsDownMessages(null, null, 5))
                .thenReturn(List.<Object[]>of(new Object[]{BigInteger.valueOf(5), BigInteger.valueOf(3)}));
        when(repo.reasonDistribution(null, null, 5))
                .thenReturn(List.<Object[]>of(new Object[]{"检索不准", BigInteger.valueOf(2)}));

        FeedbackAdminView view = buildService(repo, mock(MessageRepository.class),
                        mock(ConversationRepository.class), userService)
                .adminView(auth, null, null, 5);

        assertEquals(1, view.top().size());
        assertEquals(5L, view.top().get(0).messageId());
        assertEquals(3L, view.top().get(0).count());
        assertEquals(1, view.reasons().size());
        assertEquals("检索不准", view.reasons().get(0).reason());
        assertEquals(2L, view.reasons().get(0).count());
    }

    @Test
    void myRatings_returnsMap() {
        MessageFeedbackRepository repo = mock(MessageFeedbackRepository.class);
        UserService userService = mock(UserService.class);
        when(userService.resolveUserId("tester")).thenReturn(1L);

        MessageFeedback f = new MessageFeedback();
        f.setMessageId(5L);
        f.setRating(MessageFeedback.Rating.THUMBS_DOWN);
        when(repo.findByUserIdAndMessageIdIn(1L, List.of(5L, 6L))).thenReturn(List.of(f));

        Map<Long, String> map = buildService(repo, mock(MessageRepository.class),
                        mock(ConversationRepository.class), userService)
                .myRatings("tester", List.of(5L, 6L));

        assertEquals(1, map.size());
        assertEquals("THUMBS_DOWN", map.get(5L));
        assertTrue(map.containsKey(5L));
    }
}
