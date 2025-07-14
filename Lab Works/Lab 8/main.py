import asyncio
import time
import random
import sys
import json
import copy
import os
import threading
import math
import queue
import argparse
from typing import Dict, Tuple, List, Optional, Set, FrozenSet
from dataclasses import dataclass, field
from enum import Enum
import logging
from collections import defaultdict, deque
from math import inf
from datetime import datetime
import platform

# Check if running on macOS and set environment variable for pygame
if platform.system() == 'Darwin':
    os.environ['PYGAME_HIDE_SUPPORT_PROMPT'] = '1'

try:
    import pygame
    # Initialize pygame modules early for macOS
    pygame.init()
except ImportError:
    print("Pygame not found. Please install it using: pip install pygame")
    print("On macOS, you might need: pip3 install pygame")
    sys.exit(1)

logging.basicConfig(level=logging.WARNING, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')


class LinkStatus(Enum):
    UP = "UP"
    DOWN = "DOWN"

class RouteStatus(Enum):
    VALID = "VALID"
    INVALID = "INVALID"
    GARBAGE = "GARBAGE"

class ConvergenceState(Enum):
    CONVERGING = "CONVERGING"
    CONVERGED = "CONVERGED"
    DIVERGING = "DIVERGING"

class MessageType(Enum):
    REGULAR = "REGULAR"
    POISON_REVERSE = "POISON_REVERSE"
    TRIGGERED = "TRIGGERED"

@dataclass
class Message:
    source: str
    destination: str
    distance_vector: Dict[str, int]
    timestamp: float
    message_id: str
    message_type: MessageType = MessageType.REGULAR
    poison_reverse_routes: Set[str] = field(default_factory=set)

@dataclass
class Link:
    router1: str
    router2: str
    cost: int
    status: LinkStatus = LinkStatus.UP
    propagation_delay: float = 0.01
    packet_loss_rate: float = 0.0
    last_failure_time: float = 0

    def is_operational(self) -> bool:
        return self.status == LinkStatus.UP

    def should_drop_packet(self) -> bool:
        return random.random() < self.packet_loss_rate

    def get_endpoints(self) -> FrozenSet[str]:
        """Return endpoints as a frozenset for easy comparison"""
        return frozenset({self.router1, self.router2})

@dataclass
class RouteEntry:
    destination: str
    cost: int
    next_hop: Optional[str]
    status: RouteStatus = RouteStatus.VALID
    last_update_time: float = field(init=False)
    timeout_time: float = field(init=False)
    garbage_time: float = field(init=False)
    
    def __post_init__(self):
        current_time = time.time()
        self.last_update_time = current_time
        self.timeout_time = 0
        self.garbage_time = 0

@dataclass 
class VisualizationUpdate:
    """Thread-safe communication between simulation and visualization"""
    update_type: str  # 'event', 'message', 'route_change', 'link_change', 'convergence', 'poison_reverse', 'table_update'
    data: Dict
    timestamp: float

@dataclass
class NetworkStats:
    """Global network statistics and convergence tracking"""
    last_route_change_time: float = 0
    total_route_changes: int = 0
    total_messages: int = 0
    poison_reverse_messages: int = 0
    convergence_state: ConvergenceState = ConvergenceState.CONVERGING
    convergence_detected_at: float = 0
    periodic_updates_enabled: bool = True

class RIPTimers:
    PERIODIC_UPDATE = 15.0
    JITTER_RANGE = 0.1
    ROUTE_TIMEOUT = 90
    GARBAGE_COLLECTION = 60.0
    HOLD_DOWN = 90.0
    MIN_TRIGGERED_INTERVAL = 2.5
    
    CONVERGENCE_TIMEOUT = 45.0  


class TableLogger:
    """Logs routing table updates to files"""
    def __init__(self, output_dir="rip_logs"):
        self.output_dir = output_dir
        os.makedirs(output_dir, exist_ok=True)
        self.start_time = time.time()
        
        # Main log file
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.main_log = open(f"{output_dir}/rip_simulation_{timestamp}.log", "w")
        self.main_log.write("RIP Protocol Simulation Log\n")
        self.main_log.write(f"Started at: {datetime.now()}\n")
        self.main_log.write("="*60 + "\n\n")
        
        # Router-specific logs
        self.router_logs = {}
        
        # Poison reverse log
        self.poison_log = open(f"{output_dir}/poison_reverse_{timestamp}.log", "w")
        self.poison_log.write("Poison Reverse Events Log\n")
        self.poison_log.write("="*60 + "\n\n")
    
    def log_table_update(self, router_id: str, routing_table: Dict, event_type: str = "UPDATE"):
        """Log a routing table update"""
        if router_id not in self.router_logs:
            self.router_logs[router_id] = open(f"{self.output_dir}/router_{router_id}.log", "w")
            self.router_logs[router_id].write(f"Router {router_id} Routing Table Log\n")
            self.router_logs[router_id].write("="*60 + "\n\n")
        
        elapsed = time.time() - self.start_time
        timestamp = f"[Time: {elapsed:.1f}s] {event_type}"
        
        # Write to router-specific log
        self.router_logs[router_id].write(f"{timestamp}\n")
        self.router_logs[router_id].write(f"{'Dest':<6} | {'Cost':<6} | {'Next Hop':<10} | {'Status':<10}\n")
        self.router_logs[router_id].write("-"*45 + "\n")
        
        for dest in sorted(routing_table.keys()):
            entry = routing_table[dest]
            cost = 'inf' if entry['cost'] == inf else str(entry['cost'])
            next_hop = entry['next_hop'] if entry['next_hop'] else '-'
            status = entry['status']
            self.router_logs[router_id].write(f"{dest:<6} | {cost:<6} | {next_hop:<10} | {status:<10}\n")
        
        self.router_logs[router_id].write("\n")
        self.router_logs[router_id].flush()
        
        # Also write summary to main log
        self.main_log.write(f"{timestamp} - Router {router_id}\n")
        self.main_log.flush()
    
    def log_poison_reverse(self, source: str, dest: str, routes: Set[str]):
        """Log poison reverse event"""
        elapsed = time.time() - self.start_time
        self.poison_log.write(f"[Time: {elapsed:.1f}s] Router {source} -> Router {dest}\n")
        self.poison_log.write(f"Poisoned routes: {', '.join(sorted(routes))}\n")
        self.poison_log.write("-"*40 + "\n")
        self.poison_log.flush()
    
    def close(self):
        """Close all log files"""
        self.main_log.close()
        self.poison_log.close()
        for log in self.router_logs.values():
            log.close()


class VisualizationQueue:
    """Thread-safe communication between simulation and visualization"""
    
    def __init__(self):
        self.queue = queue.Queue(maxsize=1000)
        self.running = True
    
    def put_update(self, update_type: str, data: Dict):
        if not self.running:
            return
        try:
            update = VisualizationUpdate(
                update_type=update_type,
                data=data,
                timestamp=time.time()
            )
            self.queue.put_nowait(update)
        except queue.Full:
            # Drop oldest update if queue is full
            try:
                self.queue.get_nowait()
                self.queue.put_nowait(update)
            except queue.Empty:
                pass
    
    def get_updates(self) -> List[VisualizationUpdate]:
        updates = []
        try:
            while True:
                update = self.queue.get_nowait()
                updates.append(update)
        except queue.Empty:
            pass
        return updates
    
    def stop(self):
        self.running = False


class NetworkVisualizer:
    """Thread-safe Pygame visualization of the network simulation"""
    
    def __init__(self, network, width=1200, height=800):
        self.network = network
        self.width = width
        self.height = height
        self.screen = None
        self.font = None
        self.small_font = None
        self.tiny_font = None
        self.clock = None
        
        # Initialize table logger
        self.table_logger = TableLogger()
        
        # Colors - no pink!
        self.colors = {
            'bg': (25, 25, 35),
            'node': (70, 130, 180),
            'node_text': (255, 255, 255),
            'link_up': (60, 179, 113),
            'link_down': (220, 20, 60),
            'text': (230, 230, 230),
            'panel_bg': (40, 40, 50),
            'message': (255, 165, 0),
            'poison_message': (255, 69, 0),  # Orange-red for poison reverse
            'selected': (255, 215, 0),
            'header': (100, 149, 237),
            'converged': (50, 205, 50),
            'converging': (255, 140, 0),
            'poison_indicator': (255, 255, 255),  # Red instead of pink
        }
        
        self.router_positions = {}
        self.node_radius = 35
        self.animated_messages = deque()
        self.event_log = deque(maxlen=30)
        self.selected_router_id = None
        self.running = True
        self.show_poison_routes = True
        self.show_routing_tables = False
        
        self._cached_topology = {}
        self._cached_routing_tables = {}
        self._cached_stats = None
        self._last_cache_update = 0
        self._cache_lock = threading.Lock()

    def init_pygame(self):
        """Initialize pygame with macOS compatibility"""
        try:
            # For macOS, we need to initialize in the main thread
            if platform.system() == 'Darwin':
                # Set SDL video driver
                os.environ['SDL_VIDEODRIVER'] = 'cocoa'
            
            # Initialize display
            self.screen = pygame.display.set_mode((self.width, self.height))
            pygame.display.set_caption("RIP Protocol with Poison Reverse Visualization")
            
            # Initialize fonts
            pygame.font.init()
            self.font = pygame.font.Font(None, 24)
            self.small_font = pygame.font.Font(None, 18)
            self.tiny_font = pygame.font.Font(None, 14)
            
            # Initialize clock
            self.clock = pygame.time.Clock()
            
            # Calculate router positions
            self.calculate_router_positions()
            
            print("Pygame initialized successfully")
            
        except Exception as e:
            print(f"Error initializing pygame: {e}")
            print("Make sure you have pygame installed: pip install pygame")
            raise

    def calculate_router_positions(self):
        router_ids = list(self.network.routers.keys()) if self.network.routers else []
        if not router_ids:
            return
            
        center_x, center_y = self.width // 3, self.height // 2
        radius = min(center_x, center_y) * 0.6
        num_routers = len(router_ids)
        
        for i, router_id in enumerate(sorted(router_ids)):
            angle = (2 * math.pi / num_routers) * i - math.pi / 2
            x = center_x + radius * math.cos(angle)
            y = center_y + radius * math.sin(angle)
            self.router_positions[router_id] = (int(x), int(y))

    def update_cache(self):
        """Safely cache simulation data for visualization"""
        with self._cache_lock:
            try:
                self._cached_topology = {}
                processed_links = set()
                for link_key, link in self.network.links.items():
                    endpoints = link.get_endpoints()
                    if endpoints not in processed_links:
                        processed_links.add(endpoints)
                        self._cached_topology[endpoints] = {
                            'cost': link.cost,
                            'status': link.status.value,
                            'delay': link.propagation_delay,
                            'loss_rate': link.packet_loss_rate,
                            'router1': link.router1,
                            'router2': link.router2
                        }
                
                self._cached_routing_tables = {}
                for router_id, router in self.network.routers.items():
                    self._cached_routing_tables[router_id] = router.get_routing_table_summary()
                
                self._cached_stats = {
                    'convergence_state': self.network.stats.convergence_state.value,
                    'total_route_changes': self.network.stats.total_route_changes,
                    'total_messages': self.network.stats.total_messages,
                    'poison_reverse_messages': self.network.stats.poison_reverse_messages,
                    'last_route_change': self.network.stats.last_route_change_time,
                    'convergence_detected_at': self.network.stats.convergence_detected_at,
                    'periodic_updates_enabled': self.network.stats.periodic_updates_enabled
                }
                
                self._last_cache_update = time.time()
            except Exception as e:
                logging.error(f"Error updating cache: {e}")

    def add_event(self, text: str):
        timestamp = f"{int(time.time() - self.network.start_time):>3}s"
        self.event_log.appendleft(f"[{timestamp}] {text}")

    def add_animated_message(self, source_id: str, dest_id: str, message_type: str = "regular", poison_routes: Set[str] = None):
        if source_id in self.router_positions and dest_id in self.router_positions:
            start_pos = self.router_positions[source_id]
            end_pos = self.router_positions[dest_id]
            try:
                delay = self.network.routers[source_id].neighbors[dest_id].propagation_delay
                duration = delay * 50 + 0.8  # Scale for visibility
            except (KeyError, AttributeError):
                duration = 1.2  # Default duration
                
            color = self.colors['poison_message'] if message_type == "poison" else self.colors['message']
            
            self.animated_messages.append({
                "start_pos": start_pos,
                "end_pos": end_pos,
                "start_time": time.time(),
                "duration": duration,
                "source": source_id,
                "dest": dest_id,
                "color": color,
                "message_type": message_type,
                "poison_routes": poison_routes or set()
            })

    def process_visualization_updates(self):
        """Process updates from the simulation"""
        if not hasattr(self.network, 'viz_queue'):
            return
            
        updates = self.network.viz_queue.get_updates()
        for update in updates:
            if update.update_type == 'event':
                self.add_event(update.data['text'])
            elif update.update_type == 'message':
                self.add_animated_message(
                    update.data['source'], 
                    update.data['dest'],
                    update.data.get('message_type', 'regular'),
                    update.data.get('poison_routes', set())
                )
            elif update.update_type == 'poison_reverse':
                poison_text = f"POISON: R-{update.data['source']} => R-{update.data['dest']}: {', '.join(update.data['routes'])}"
                self.add_event(poison_text)
                # Log poison reverse
                self.table_logger.log_poison_reverse(
                    update.data['source'],
                    update.data['dest'],
                    update.data['routes']
                )
            elif update.update_type == 'convergence':
                convergence_text = f"Network {update.data['state'].lower()}"
                if update.data['state'] == 'CONVERGED':
                    convergence_text += f" after {update.data.get('time_to_converge', 0):.1f}s"
                self.add_event(convergence_text)
            elif update.update_type == 'table_update':
                # Log table update
                router_id = update.data['router_id']
                event_type = update.data.get('event_type', 'UPDATE')
                if router_id in self._cached_routing_tables:
                    self.table_logger.log_table_update(
                        router_id,
                        self._cached_routing_tables[router_id],
                        event_type
                    )

    def run(self):
        """Main pygame loop with proper error handling"""
        try:
            self.init_pygame()
            
            # Log initial routing tables
            for router_id in sorted(self.network.routers.keys()):
                router = self.network.routers[router_id]
                table = router.get_routing_table_summary()
                self.table_logger.log_table_update(router_id, table, "INITIAL")
            
            print("\nSimulation started! Controls:")
            print("- Click on routers to select them")
            print("- SPACE: Toggle periodic updates")
            print("- R: Restart simulation")
            print("- P: Toggle poison route display")
            print("- T: Toggle routing table display")
            print("- ESC: Exit")
            print(f"\nLogs are being saved to: {os.path.abspath('rip_logs')}/")
            
            while self.running:
                # Handle pygame events
                for event in pygame.event.get():
                    if event.type == pygame.QUIT:
                        self.running = False
                    elif event.type == pygame.MOUSEBUTTONDOWN:
                        self.handle_click(event.pos)
                    elif event.type == pygame.KEYDOWN:
                        if event.key == pygame.K_ESCAPE:
                            self.running = False
                        elif event.key == pygame.K_SPACE:
                            self.network.toggle_periodic_updates()
                        elif event.key == pygame.K_r:
                            self.network.restart_simulation()
                        elif event.key == pygame.K_p:
                            self.show_poison_routes = not self.show_poison_routes
                            self.add_event(f"Poison route display {'ON' if self.show_poison_routes else 'OFF'}")
                        elif event.key == pygame.K_t:
                            self.show_routing_tables = not self.show_routing_tables
                            self.add_event(f"Routing table display {'ON' if self.show_routing_tables else 'OFF'}")

                self.process_visualization_updates()
                
                if time.time() - self._last_cache_update > 1.0:
                    self.update_cache()

                self.draw_frame()
                self.clock.tick(30)  # Reduced from 60 to 30 FPS for better performance
                
        except Exception as e:
            logging.error(f"Visualization error: {e}")
            print(f"Visualization error: {e}")
        finally:
            self.cleanup()

    def cleanup(self):
        self.running = False
        if hasattr(self.network, 'viz_queue'):
            self.network.viz_queue.stop()
        self.table_logger.close()
        pygame.quit()

    def handle_click(self, pos):
        for router_id, router_pos in self.router_positions.items():
            dist_sq = (pos[0] - router_pos[0])**2 + (pos[1] - router_pos[1])**2
            if dist_sq < self.node_radius**2:
                self.selected_router_id = router_id
                self.add_event(f"Selected router {router_id}")
                return
        self.selected_router_id = None

    def draw_frame(self):
        self.screen.fill(self.colors['bg'])
        
        with self._cache_lock:
            self.draw_links()
            self.draw_animated_messages()
            self.draw_routers()
            self.draw_poison_reverse_indicators()
            self.draw_info_panel()
            
            if self.show_routing_tables:
                self.draw_routing_tables_panel()
        
        pygame.display.flip()

    def draw_routers(self):
        for router_id, pos in self.router_positions.items():
            color = self.colors['selected'] if router_id == self.selected_router_id else self.colors['node']
            pygame.draw.circle(self.screen, color, pos, self.node_radius)
            pygame.draw.circle(self.screen, self.colors['text'], pos, self.node_radius, 2)
            
            text_surf = self.font.render(router_id, True, self.colors['node_text'])
            text_rect = text_surf.get_rect(center=pos)
            self.screen.blit(text_surf, text_rect)

    def draw_poison_reverse_indicators(self):
        """Draw indicators for routes that would trigger poison reverse"""
        if not self.show_poison_routes or not self.selected_router_id:
            return
            
        if self.selected_router_id not in self._cached_routing_tables:
            return
            
        routing_table = self._cached_routing_tables[self.selected_router_id]
        selected_pos = self.router_positions[self.selected_router_id]
        
        # Draw poison reverse indicators
        for dest, entry in routing_table.items():
            if (dest != self.selected_router_id and 
                entry['next_hop'] and 
                entry['next_hop'] in self.router_positions and
                entry['status'] == 'VALID'):
                
                next_hop_pos = self.router_positions[entry['next_hop']]
                dest_pos = self.router_positions.get(dest)
                
                if dest_pos:
                    mid_x = (selected_pos[0] + next_hop_pos[0]) // 2
                    mid_y = (selected_pos[1] + next_hop_pos[1]) // 2
                    
                    pygame.draw.circle(self.screen, self.colors['poison_indicator'], (mid_x, mid_y), 4)
                    
                    if entry['cost'] != inf:
                        label = f"P:{dest}"
                        label_surf = self.tiny_font.render(label, True, self.colors['poison_indicator'])
                        self.screen.blit(label_surf, (mid_x + 8, mid_y - 8))

    def draw_links(self):
        for endpoints, link_data in self._cached_topology.items():
            r1, r2 = link_data['router1'], link_data['router2']
            
            if r1 not in self.router_positions or r2 not in self.router_positions:
                continue
                
            pos1 = self.router_positions[r1]
            pos2 = self.router_positions[r2]

            color = self.colors['link_up'] if link_data['status'] == 'UP' else self.colors['link_down']
            width = 3 if link_data['status'] == 'UP' else 2
            
            pygame.draw.line(self.screen, color, pos1, pos2, width)

            mid_x = (pos1[0] + pos2[0]) // 2
            mid_y = (pos1[1] + pos2[1]) // 2 - 15
            
            cost_text = str(link_data['cost'])
            text_surf = self.small_font.render(cost_text, True, self.colors['text'])
            text_rect = text_surf.get_rect(center=(mid_x, mid_y))
            
            pygame.draw.rect(self.screen, self.colors['bg'], text_rect.inflate(6, 2))
            self.screen.blit(text_surf, text_rect)

    def draw_animated_messages(self):
        current_time = time.time()
        active_messages = deque()

        while self.animated_messages:
            msg = self.animated_messages.popleft()
            elapsed = current_time - msg['start_time']
            
            if elapsed < msg['duration']:
                progress = elapsed / msg['duration']
                start_x, start_y = msg['start_pos']
                end_x, end_y = msg['end_pos']
                
                progress = 1 - (1 - progress) ** 2  # Ease-out
                curr_x = int(start_x + (end_x - start_x) * progress)
                curr_y = int(start_y + (end_y - start_y) * progress)
                
                # Use message-specific color
                message_color = msg.get('color', self.colors['message'])
                
                pygame.draw.circle(self.screen, message_color, (curr_x, curr_y), 8)
                pygame.draw.circle(self.screen, self.colors['text'], (curr_x, curr_y), 8, 1)
                
                # Add "P" indicator for poison reverse messages
                if msg.get('message_type') == 'poison':
                    p_surf = self.tiny_font.render('P', True, self.colors['text'])
                    p_rect = p_surf.get_rect(center=(curr_x, curr_y))
                    self.screen.blit(p_surf, p_rect)
                
                active_messages.append(msg)

        self.animated_messages = active_messages

    def draw_info_panel(self):
        panel_x = self.width * 2 // 3
        panel_width = self.width - panel_x - 20
        panel_rect = pygame.Rect(panel_x, 20, panel_width, self.height - 40)
        
        pygame.draw.rect(self.screen, self.colors['panel_bg'], panel_rect, border_radius=10)
        pygame.draw.rect(self.screen, self.colors['text'], panel_rect, 2, border_radius=10)

        y_offset = 40
        
        if self._cached_stats:
            convergence_state = self._cached_stats['convergence_state']
            state_color = self.colors['converged'] if convergence_state == 'CONVERGED' else self.colors['converging']
            title_text = f"Network Status: {convergence_state}"
        else:
            title_text = "Network Information"
            state_color = self.colors['header']
            
        title_surf = self.font.render(title_text, True, state_color)
        self.screen.blit(title_surf, (panel_x + 20, y_offset))
        y_offset += 35

        controls_text = "SPACE=Updates | R=Restart | P=Poison | T=Tables"
        controls_surf = self.tiny_font.render(controls_text, True, self.colors['text'])
        self.screen.blit(controls_surf, (panel_x + 20, y_offset))
        y_offset += 25

        log_title = self.small_font.render("Event Log", True, self.colors['header'])
        self.screen.blit(log_title, (panel_x + 20, y_offset))
        y_offset += 25
        
        for i, event_text in enumerate(list(self.event_log)[:10]):
            if y_offset > panel_rect.bottom - 300:
                break
            
            # Color poison reverse events differently
            text_color = self.colors['poison_indicator'] if 'POISON:' in event_text else self.colors['text']
            event_surf = self.tiny_font.render(event_text, True, text_color)
            self.screen.blit(event_surf, (panel_x + 25, y_offset))
            y_offset += 16

        y_offset += 20
        if self.selected_router_id and self.selected_router_id in self._cached_routing_tables:
            table_title = f"Router {self.selected_router_id} Routing Table"
            title_surf = self.small_font.render(table_title, True, self.colors['header'])
            self.screen.blit(title_surf, (panel_x + 20, y_offset))
            y_offset += 25

            header = "Dest  Cost  Next Hop  Status"
            header_surf = self.tiny_font.render(header, True, self.colors['text'])
            self.screen.blit(header_surf, (panel_x + 25, y_offset))
            y_offset += 18

            pygame.draw.line(self.screen, self.colors['text'], 
                           (panel_x + 25, y_offset), 
                           (panel_x + panel_width - 25, y_offset))
            y_offset += 8

            routing_table = self._cached_routing_tables[self.selected_router_id]
            for dest in sorted(routing_table.keys()):
                if y_offset > panel_rect.bottom - 120:
                    break
                    
                entry = routing_table[dest]
                if entry['status'] == 'GARBAGE':
                    continue
                    
                cost = 'inf' if entry['cost'] == inf else str(entry['cost'])
                next_hop = entry['next_hop'] if entry['next_hop'] else '-'
                status = entry['status']
                
                if status == 'VALID':
                    text_color = self.colors['link_up']
                elif status == 'INVALID':
                    text_color = self.colors['link_down']
                else:
                    text_color = self.colors['text']

                row_text = f"{dest:<4}  {cost:<4}  {next_hop:<8}  {status}"
                row_surf = self.tiny_font.render(row_text, True, text_color)
                self.screen.blit(row_surf, (panel_x + 25, y_offset))
                y_offset += 14

        if y_offset < panel_rect.bottom - 100 and self._cached_stats:
            y_offset += 20
            stats_title = self.small_font.render("Network Statistics", True, self.colors['header'])
            self.screen.blit(stats_title, (panel_x + 20, y_offset))
            y_offset += 25
            
            elapsed = int(time.time() - self.network.start_time) if self.network.start_time > 0 else 0
            stats = [
                f"Simulation time: {elapsed}s",
                f"Route changes: {self._cached_stats['total_route_changes']}",
                f"Messages sent: {self._cached_stats['total_messages']}",
                f"Poison reverse msgs: {self._cached_stats['poison_reverse_messages']}",
                f"Periodic updates: {'ON' if self._cached_stats['periodic_updates_enabled'] else 'OFF'}",
                f"Poison indicators: {'ON' if self.show_poison_routes else 'OFF'}"
            ]
            
            if self._cached_stats['convergence_state'] == 'CONVERGED':
                convergence_time = self._cached_stats['convergence_detected_at'] - self.network.start_time
                stats.append(f"Converged at: {convergence_time:.1f}s")
            
            for stat in stats:
                if y_offset > panel_rect.bottom - 30:
                    break
                stat_surf = self.tiny_font.render(stat, True, self.colors['text'])
                self.screen.blit(stat_surf, (panel_x + 25, y_offset))
                y_offset += 16

    def draw_routing_tables_panel(self):
        """Draw a panel showing all routing tables"""
        panel_y = self.height // 2
        panel_height = self.height // 2 - 40
        panel_rect = pygame.Rect(20, panel_y, self.width * 2 // 3 - 40, panel_height)
        
        # Semi-transparent background
        s = pygame.Surface((panel_rect.width, panel_rect.height))
        s.set_alpha(240)
        s.fill(self.colors['panel_bg'])
        self.screen.blit(s, (panel_rect.x, panel_rect.y))
        
        pygame.draw.rect(self.screen, self.colors['text'], panel_rect, 2, border_radius=10)
        
        # Title
        title = "All Routing Tables"
        title_surf = self.font.render(title, True, self.colors['header'])
        self.screen.blit(title_surf, (panel_rect.x + 20, panel_rect.y + 10))
        
        # Draw tables in grid
        routers = sorted(self.network.routers.keys())
        cols = 3
        col_width = panel_rect.width // cols
        
        for i, router_id in enumerate(routers):
            if router_id not in self._cached_routing_tables:
                continue
                
            col = i % cols
            row = i // cols
            x = panel_rect.x + 10 + col * col_width
            y = panel_rect.y + 50 + row * 120
            
            # Router title
            router_title = f"Router {router_id}"
            color = self.colors['selected'] if router_id == self.selected_router_id else self.colors['text']
            title_surf = self.small_font.render(router_title, True, color)
            self.screen.blit(title_surf, (x, y))
            
            # Table header
            y += 20
            header = "D  C  NH"
            header_surf = self.tiny_font.render(header, True, self.colors['text'])
            self.screen.blit(header_surf, (x, y))
            
            # Table entries
            y += 15
            table = self._cached_routing_tables[router_id]
            for dest in sorted(table.keys()):
                if y > panel_rect.bottom - 20:
                    break
                    
                entry = table[dest]
                if entry['status'] == 'GARBAGE':
                    continue
                
                cost = 'I' if entry['cost'] == inf else str(entry['cost'])
                nh = entry['next_hop'] if entry['next_hop'] else '-'
                
                color = self.colors['link_up'] if entry['status'] == 'VALID' else self.colors['link_down']
                
                row = f"{dest} {cost:>2} {nh:>2}"
                row_surf = self.tiny_font.render(row, True, color)
                self.screen.blit(row_surf, (x, y))
                y += 12


class AsyncRouter:
    def __init__(self, router_id: str, network):
        self.id = router_id
        self.network = network
        self.neighbors = {}
        self.routing_table = {}
        self.distance_vector = {}
        
        self.inbox = asyncio.Queue()
        self.running = False
        self.logger = logging.getLogger(f"Router-{self.id}")
        self.hold_down_routes = {}
        
        self.last_periodic_update = 0
        self.last_triggered_update = 0
        self.periodic_interval = self._calculate_periodic_interval()

    def _calculate_periodic_interval(self) -> float:
        jitter = random.uniform(-RIPTimers.JITTER_RANGE, RIPTimers.JITTER_RANGE)
        return RIPTimers.PERIODIC_UPDATE * (1 + jitter)

    def add_neighbor(self, neighbor_id: str, link: Link):
        self.neighbors[neighbor_id] = link

    def initialize_routing_table(self, all_routers: List[str]):
        current_time = time.time()
        for dest in all_routers:
            if dest == self.id:
                entry = RouteEntry(
                    destination=dest, 
                    cost=0, 
                    next_hop=self.id, 
                    status=RouteStatus.VALID
                )
            else:
                entry = RouteEntry(
                    destination=dest, 
                    cost=inf, 
                    next_hop=None, 
                    status=RouteStatus.INVALID
                )
            self.routing_table[dest] = entry
            self.distance_vector[dest] = entry.cost

        for neighbor_id, link in self.neighbors.items():
            if link.is_operational() and neighbor_id in self.routing_table:
                entry = self.routing_table[neighbor_id]
                entry.cost = link.cost
                entry.next_hop = neighbor_id
                entry.status = RouteStatus.VALID
                entry.last_update_time = current_time
                self.distance_vector[neighbor_id] = link.cost

    def get_distance_vector_for_neighbor(self, neighbor_id: str) -> Tuple[Dict[str, int], Set[str]]:
        """
        Returns distance vector and set of routes being poison reversed
        """
        vector = {}
        poison_routes = set()
        current_time = time.time()
        
        for dest, entry in self.routing_table.items():
            if entry.status == RouteStatus.GARBAGE:
                continue
            
            cost = entry.cost
            
            # Implement poison reverse
            if entry.next_hop == neighbor_id and dest != self.id:
                cost = inf
                poison_routes.add(dest)
                self.logger.debug(f"Poison reverse: R-{self.id} → R-{neighbor_id} for dest {dest}")
            
            if dest in self.hold_down_routes:
                if current_time < self.hold_down_routes[dest]:
                    continue
                else:
                    del self.hold_down_routes[dest]
            
            vector[dest] = cost
            
        return vector, poison_routes

    async def send_message(self, neighbor_id: str, distance_vector: Dict[str, int], poison_routes: Set[str] = None):
        if neighbor_id not in self.neighbors:
            return
        
        link = self.neighbors[neighbor_id]
        if not link.is_operational():
            return
        
        if link.should_drop_packet():
            self.logger.debug(f"Message to {neighbor_id} dropped")
            return

        self.network.stats.total_messages += 1
        
        if poison_routes:
            self.network.stats.poison_reverse_messages += 1
            message_type = MessageType.POISON_REVERSE
            
            if hasattr(self.network, 'viz_queue'):
                self.network.viz_queue.put_update('poison_reverse', {
                    'source': self.id,
                    'dest': neighbor_id,
                    'routes': list(poison_routes)
                })
        else:
            message_type = MessageType.REGULAR

        if hasattr(self.network, 'viz_queue'):
            self.network.viz_queue.put_update('message', {
                'source': self.id,
                'dest': neighbor_id,
                'message_type': 'poison' if poison_routes else 'regular',
                'poison_routes': poison_routes or set()
            })

        message = Message(
            source=self.id,
            destination=neighbor_id,
            distance_vector=distance_vector,
            timestamp=time.time(),
            message_id=f"{self.id}-{neighbor_id}-{int(time.time()*1000)}",
            message_type=message_type,
            poison_reverse_routes=poison_routes or set()
        )

        await asyncio.sleep(link.propagation_delay)
        await self.network.deliver_message(message)

    async def receive_message(self, message: Message):
        await self.inbox.put(message)

    async def process_message(self, message: Message) -> bool:
        if (message.source not in self.neighbors or 
            not self.neighbors[message.source].is_operational()):
            return False
        updated = self.update_routing_table(message.source, message.distance_vector)
        
        # Log table update if changed
        if updated and hasattr(self.network, 'viz_queue'):
            self.network.viz_queue.put_update('table_update', {
                'router_id': self.id,
                'event_type': 'MESSAGE_UPDATE'
            })
        
        return updated

    def update_routing_table(self, from_neighbor: str, received_vector: Dict[str, int]) -> bool:
        updated = False
        current_time = time.time()
        
        if from_neighbor not in self.neighbors:
            return False
        
        neighbor_cost = self.neighbors[from_neighbor].cost
        
        if from_neighbor in self.routing_table:
            self.routing_table[from_neighbor].last_update_time = current_time
            self.routing_table[from_neighbor].timeout_time = current_time + RIPTimers.ROUTE_TIMEOUT

        for dest, neighbor_cost_to_dest in received_vector.items():
            if dest == self.id:
                continue
            
            new_cost = (inf if neighbor_cost_to_dest == inf 
                       else neighbor_cost + neighbor_cost_to_dest)
            
            if dest not in self.routing_table:
                self.routing_table[dest] = RouteEntry(
                    destination=dest,
                    cost=inf,
                    next_hop=None,
                    status=RouteStatus.INVALID
                )
            
            entry = self.routing_table[dest]
            old_cost = entry.cost
            route_changed = False

            if entry.next_hop == from_neighbor:
                if new_cost != old_cost:
                    entry.cost = new_cost
                    entry.last_update_time = current_time
                    entry.timeout_time = current_time + RIPTimers.ROUTE_TIMEOUT
                    
                    if new_cost == inf:
                        entry.status = RouteStatus.INVALID
                        entry.garbage_time = current_time + RIPTimers.GARBAGE_COLLECTION
                        self.hold_down_routes[dest] = current_time + RIPTimers.HOLD_DOWN
                    else:
                        entry.status = RouteStatus.VALID
                        entry.garbage_time = 0
                    
                    route_changed = True
            
            elif new_cost < entry.cost and new_cost != inf:
                if dest not in self.hold_down_routes:
                    entry.cost = new_cost
                    entry.next_hop = from_neighbor
                    entry.status = RouteStatus.VALID
                    entry.last_update_time = current_time
                    entry.timeout_time = current_time + RIPTimers.ROUTE_TIMEOUT
                    entry.garbage_time = 0
                    route_changed = True

            if route_changed:
                self.distance_vector[dest] = entry.cost
                updated = True
                
                self.network.stats.last_route_change_time = current_time
                self.network.stats.total_route_changes += 1
                self.network.stats.convergence_state = ConvergenceState.CONVERGING
                
                self.logger.info(f"Route to {dest}: {old_cost} -> {entry.cost} via {entry.next_hop}")

        return updated

    async def send_periodic_update(self):
        if not self.network.stats.periodic_updates_enabled:
            return
            
        for neighbor_id in self.neighbors:
            if self.neighbors[neighbor_id].is_operational():
                dv, poison_routes = self.get_distance_vector_for_neighbor(neighbor_id)
                await self.send_message(neighbor_id, dv, poison_routes)
        
        self.last_periodic_update = time.time()
        self.periodic_interval = self._calculate_periodic_interval()

    async def send_triggered_update(self):
        current_time = time.time()
        if current_time - self.last_triggered_update < RIPTimers.MIN_TRIGGERED_INTERVAL:
            return
        
        for neighbor_id in self.neighbors:
            if self.neighbors[neighbor_id].is_operational():
                dv, poison_routes = self.get_distance_vector_for_neighbor(neighbor_id)
                await self.send_message(neighbor_id, dv, poison_routes)
        
        self.last_triggered_update = current_time

    async def check_timeouts(self):
        current_time = time.time()
        routes_changed = False
        
        for dest, entry in list(self.routing_table.items()):
            if dest == self.id:
                continue
            
            if (entry.status == RouteStatus.VALID and 
                entry.timeout_time > 0 and 
                current_time > entry.timeout_time):
                
                self.logger.warning(f"Route to {dest} timed out")
                if hasattr(self.network, 'viz_queue'):
                    self.network.viz_queue.put_update('event', {
                        'text': f"R-{self.id}: Route to {dest} timed out"
                    })
                
                entry.status = RouteStatus.INVALID
                entry.cost = inf
                entry.garbage_time = current_time + RIPTimers.GARBAGE_COLLECTION
                self.distance_vector[dest] = inf
                self.hold_down_routes[dest] = current_time + RIPTimers.HOLD_DOWN
                routes_changed = True
            
            elif (entry.status == RouteStatus.INVALID and 
                  entry.garbage_time > 0 and 
                  current_time > entry.garbage_time):
                
                self.logger.warning(f"Garbage collecting route to {dest}")
                entry.status = RouteStatus.GARBAGE
                routes_changed = True

        if routes_changed:
            self.network.stats.last_route_change_time = current_time
            self.network.stats.convergence_state = ConvergenceState.CONVERGING
            
            # Log table update
            if hasattr(self.network, 'viz_queue'):
                self.network.viz_queue.put_update('table_update', {
                    'router_id': self.id,
                    'event_type': 'TIMEOUT'
                })
            
            await self.send_triggered_update()

    async def handle_link_failure(self, neighbor_id: str):
        if neighbor_id not in self.neighbors:
            return
        
        self.logger.warning(f"Link to {neighbor_id} failed")
        routes_changed = False
        current_time = time.time()
        
        for dest, entry in self.routing_table.items():
            if entry.next_hop == neighbor_id and dest != self.id:
                entry.cost = inf
                entry.status = RouteStatus.INVALID
                entry.garbage_time = current_time + RIPTimers.GARBAGE_COLLECTION
                self.distance_vector[dest] = inf
                self.hold_down_routes[dest] = current_time + RIPTimers.HOLD_DOWN
                routes_changed = True

        if routes_changed:
            self.network.stats.last_route_change_time = current_time
            self.network.stats.convergence_state = ConvergenceState.CONVERGING
            
            # Log table update
            if hasattr(self.network, 'viz_queue'):
                self.network.viz_queue.put_update('table_update', {
                    'router_id': self.id,
                    'event_type': 'LINK_FAILURE'
                })
            
            await self.send_triggered_update()

    async def handle_link_recovery(self, neighbor_id: str, new_cost: int):
        if neighbor_id not in self.neighbors:
            return
        
        self.logger.info(f"Link to {neighbor_id} recovered with cost {new_cost}")
        current_time = time.time()
        
        if neighbor_id in self.routing_table:
            entry = self.routing_table[neighbor_id]
            entry.cost = new_cost
            entry.next_hop = neighbor_id
            entry.status = RouteStatus.VALID
            entry.last_update_time = current_time
            entry.timeout_time = current_time + RIPTimers.ROUTE_TIMEOUT
            self.distance_vector[neighbor_id] = new_cost
            
            self.network.stats.last_route_change_time = current_time
            self.network.stats.convergence_state = ConvergenceState.CONVERGING
            
            # Log table update
            if hasattr(self.network, 'viz_queue'):
                self.network.viz_queue.put_update('table_update', {
                    'router_id': self.id,
                    'event_type': 'LINK_RECOVERY'
                })
            
            await self.send_triggered_update()

    async def run(self):
        self.running = True
        self.logger.info(f"Router {self.id} starting")
        await asyncio.sleep(random.uniform(1, 5))  # Startup jitter
        
        while self.running:
            try:
                current_time = time.time()

                if current_time - self.last_periodic_update >= self.periodic_interval:
                    await self.send_periodic_update()

                try:
                    message = await asyncio.wait_for(self.inbox.get(), timeout=1.0)
                    if await self.process_message(message):
                        await self.send_triggered_update()
                except asyncio.TimeoutError:
                    pass

                await self.check_timeouts()
                await asyncio.sleep(0.1)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Error in router loop: {e}")
                await asyncio.sleep(1)

    def stop(self):
        self.running = False

    def get_routing_table_summary(self) -> Dict:
        summary = {}
        for dest, entry in self.routing_table.items():
            summary[dest] = {
                'cost': entry.cost,
                'next_hop': entry.next_hop,
                'status': entry.status.value,
                'last_update': entry.last_update_time
            }
        return summary


class AsyncNetwork:
    def __init__(self, topology_file: str = 'topology.json', random_seed: Optional[int] = None):
        self.routers = {}
        self.links = {}  # Keep string-based keys for backward compatibility
        self.topology_file = topology_file
        self.message_queue = asyncio.Queue()
        self.running = False
        self.start_time = 0
        self.logger = logging.getLogger("Network")
        
        if random_seed is not None:
            random.seed(random_seed)
            self.logger.info(f"Random seed set to {random_seed}")
        
        self.stats = NetworkStats()
        
        self.viz_queue = VisualizationQueue()
        
        self.tasks = []
        self.loop = None

    def load_default_topology(self):
        """Load a default topology for demonstration"""
        default_topology = {
            'links': [
                {'router1': 'A', 'router2': 'B', 'cost': 2, 'delay': 0.01, 'loss_rate': 0.0},
                {'router1': 'A', 'router2': 'C', 'cost': 5, 'delay': 0.01, 'loss_rate': 0.0},
                {'router1': 'B', 'router2': 'C', 'cost': 1, 'delay': 0.01, 'loss_rate': 0.0},
                {'router1': 'B', 'router2': 'D', 'cost': 3, 'delay': 0.01, 'loss_rate': 0.0},
                {'router1': 'C', 'router2': 'D', 'cost': 2, 'delay': 0.01, 'loss_rate': 0.0}
            ]
        }
        self._load_json_topology(default_topology)

    def _load_json_topology(self, topology_data: Dict):
        router_ids = set()
        
        for link_data in topology_data['links']:
            r1, r2 = link_data['router1'], link_data['router2']
            cost = link_data['cost']
            delay = link_data.get('delay', 0.01)
            loss_rate = link_data.get('loss_rate', 0.0)
            
            router_ids.update([r1, r2])
            
            link = Link(
                router1=r1,
                router2=r2,
                cost=cost,
                propagation_delay=delay,
                packet_loss_rate=loss_rate
            )
            
            self.links[(r1, r2)] = link
            self.links[(r2, r1)] = link

        for router_id in router_ids:
            self.routers[router_id] = AsyncRouter(router_id, self)
        
        for router_id, router in self.routers.items():
            for (r1, r2), link in self.links.items():
                if r1 == router_id:
                    router.add_neighbor(r2, link)

    def initialize_routing_tables(self):
        all_router_ids = list(self.routers.keys())
        for router in self.routers.values():
            router.initialize_routing_table(all_router_ids)

    async def deliver_message(self, message: Message):
        await self.message_queue.put(message)

    async def message_delivery_loop(self):
        while self.running:
            try:
                message = await asyncio.wait_for(self.message_queue.get(), timeout=1.0)
                if message.destination in self.routers:
                    await self.routers[message.destination].receive_message(message)
            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Error in message delivery: {e}")

    async def convergence_monitor(self):
        """Monitor network convergence"""
        while self.running:
            try:
                await asyncio.sleep(5)
                current_time = time.time()
                
                if (self.stats.convergence_state == ConvergenceState.CONVERGING and
                    current_time - self.stats.last_route_change_time > RIPTimers.CONVERGENCE_TIMEOUT):
                    
                    self.stats.convergence_state = ConvergenceState.CONVERGED
                    self.stats.convergence_detected_at = current_time
                    convergence_time = current_time - self.start_time
                    
                    self.logger.info(f"Network converged after {convergence_time:.1f} seconds")
                    self.viz_queue.put_update('convergence', {
                        'state': 'CONVERGED',
                        'time_to_converge': convergence_time
                    })
                    self.viz_queue.put_update('event', {
                        'text': f"Network converged! ({convergence_time:.1f}s)"
                    })
                    
                    # Log final converged tables
                    for router_id, router in self.routers.items():
                        self.viz_queue.put_update('table_update', {
                            'router_id': router_id,
                            'event_type': 'CONVERGED'
                        })
                    
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Error in convergence monitor: {e}")

    async def simulate_link_dynamics(self):
        await asyncio.sleep(20)
        
        while self.running:
            try:
                await asyncio.sleep(random.uniform(10, 20))
                if not self.running:
                    break
                
                failure_type = random.choices(
                    ['link_failure', 'cost_change', 'node_failure'],
                    weights=[0.4, 0.5, 0.1]
                )[0]
                
                if failure_type == 'node_failure':
                    await self._simulate_node_failure()
                elif failure_type == 'link_failure':
                    await self._simulate_single_link_failure()
                else:
                    await self._simulate_cost_change()
                    
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Error in link dynamics: {e}")

    async def _simulate_node_failure(self):
        """Simulate correlated failure: entire node goes down"""
        router_candidates = [r for r in self.routers.keys() if self.routers[r].neighbors]
        if not router_candidates:
            return
            
        failed_router = random.choice(router_candidates)
        
        event_text = f"NODE FAILURE: Router {failed_router} offline"
        self.logger.warning(event_text)
        self.viz_queue.put_update('event', {'text': event_text})
        
        failed_links = []
        for neighbor_id in list(self.routers[failed_router].neighbors.keys()):
            link_key = (failed_router, neighbor_id)
            if link_key in self.links:
                link = self.links[link_key]
                if link.status == LinkStatus.UP:
                    link.status = LinkStatus.DOWN
                    failed_links.append((failed_router, neighbor_id, link))
                    
                    await self.routers[failed_router].handle_link_failure(neighbor_id)
                    await self.routers[neighbor_id].handle_link_failure(failed_router)
        
        for r1, r2, link in failed_links:
            asyncio.create_task(self._schedule_link_recovery(r1, r2, link))

    async def _simulate_single_link_failure(self):
        """Simulate single link failure"""
        active_links = [
            (k, v) for k, v in self.links.items() 
            if v.status == LinkStatus.UP and k[0] < k[1]
        ]
        
        if not active_links:
            return
            
        (r1, r2), link = random.choice(active_links)
        await self._simulate_link_failure(r1, r2, link)

    async def _simulate_link_failure(self, r1: str, r2: str, link: Link):
        event_text = f"LINK FAILURE: {r1} <-> {r2}"
        self.logger.warning(event_text)
        self.viz_queue.put_update('event', {'text': event_text})
        self.viz_queue.put_update('link_change', {'r1': r1, 'r2': r2, 'status': 'DOWN'})
        
        link.status = LinkStatus.DOWN
        link.last_failure_time = time.time()
        
        if r1 in self.routers:
            await self.routers[r1].handle_link_failure(r2)
        if r2 in self.routers:
            await self.routers[r2].handle_link_failure(r1)
        
        asyncio.create_task(self._schedule_link_recovery(r1, r2, link))

    async def _schedule_link_recovery(self, r1: str, r2: str, link: Link):
        try:
            await asyncio.sleep(random.uniform(15, 25))
            if self.running and link.status == LinkStatus.DOWN:
                event_text = f"LINK RECOVERY: {r1} <-> {r2}"
                self.logger.info(event_text)
                self.viz_queue.put_update('event', {'text': event_text})
                self.viz_queue.put_update('link_change', {'r1': r1, 'r2': r2, 'status': 'UP'})
                
                link.status = LinkStatus.UP
                
                if r1 in self.routers:
                    await self.routers[r1].handle_link_recovery(r2, link.cost)
                if r2 in self.routers:
                    await self.routers[r2].handle_link_recovery(r1, link.cost)
        except asyncio.CancelledError:
            pass

    async def _simulate_cost_change(self):
        active_links = [
            (k, v) for k, v in self.links.items() 
            if v.status == LinkStatus.UP and k[0] < k[1]
        ]
        
        if not active_links:
            return
            
        (r1, r2), link = random.choice(active_links)
        old_cost = link.cost
        new_cost = random.randint(1, 10)
        
        if new_cost != old_cost:
            event_text = f"COST CHANGE: {r1}<->{r2}: {old_cost} -> {new_cost}"
            self.logger.info(event_text)
            self.viz_queue.put_update('event', {'text': event_text})
            
            link.cost = new_cost
            
            if r1 in self.routers:
                self.routers[r1].neighbors[r2].cost = new_cost
                await self.routers[r1].handle_link_recovery(r2, new_cost)
            if r2 in self.routers:
                self.routers[r2].neighbors[r1].cost = new_cost
                await self.routers[r2].handle_link_recovery(r1, new_cost)

    def toggle_periodic_updates(self):
        """Toggle periodic updates on/off"""
        self.stats.periodic_updates_enabled = not self.stats.periodic_updates_enabled
        status = "enabled" if self.stats.periodic_updates_enabled else "disabled"
        self.viz_queue.put_update('event', {'text': f"Periodic updates {status}"})

    def restart_simulation(self):
        """Restart the simulation"""
        self.stats = NetworkStats()
        self.stats.periodic_updates_enabled = True
        
        self.initialize_routing_tables()
        
        for link in self.links.values():
            link.status = LinkStatus.UP
        
        self.viz_queue.put_update('event', {'text': 'Simulation restarted'})

    async def run_simulation(self):
        """Main simulation loop with proper event loop management"""
        try:
            self.loop = asyncio.get_running_loop()
            self.running = True
            self.start_time = time.time()
            self.viz_queue.put_update('event', {'text': 'Simulation started'})
            
            router_tasks = [asyncio.create_task(router.run()) for router in self.routers.values()]
            message_task = asyncio.create_task(self.message_delivery_loop())
            dynamics_task = asyncio.create_task(self.simulate_link_dynamics())
            convergence_task = asyncio.create_task(self.convergence_monitor())
            
            self.tasks = router_tasks + [message_task, dynamics_task, convergence_task]
            
            await asyncio.gather(*self.tasks, return_exceptions=True)
            
        except Exception as e:
            self.logger.error(f"Simulation error: {e}")
        finally:
            await self.cleanup()

    async def cleanup(self):
        """Clean shutdown of simulation"""
        self.running = False
        
        for router in self.routers.values():
            router.stop()
        
        for task in self.tasks:
            if not task.done():
                task.cancel()
        
        if self.tasks:
            await asyncio.gather(*self.tasks, return_exceptions=True)
        
        self.tasks.clear()
        
        self.viz_queue.stop()

    def stop_simulation(self):
        """Stop simulation (can be called from other threads)"""
        self.running = False


def run_simulation_thread(network):
    """Run the asyncio simulation with proper event loop management"""
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
        loop.run_until_complete(network.run_simulation())
        
    except Exception as e:
        logging.error(f"Simulation thread error: {e}")
    finally:
        try:    
            loop.close()
        except:
            pass


def parse_arguments():
    """Parse command line arguments"""
    parser = argparse.ArgumentParser(description="Visual RIP Protocol Simulation with Poison Reverse")
    parser.add_argument('--seed', type=int, help='Random seed for reproducibility')
    parser.add_argument('--topology', type=str, help='Path to topology JSON file')
    parser.add_argument('--width', type=int, default=1200, help='Window width')
    parser.add_argument('--height', type=int, default=800, help='Window height')
    return parser.parse_args()


def main():
    """Main function with improved architecture and argument parsing"""
    args = parse_arguments()
    
    print("RIP Protocol Visualization with Poison Reverse")
    print("=" * 50)
    
    if args.seed:
        print(f"Using random seed: {args.seed}")
    
    # Initialize network
    network = AsyncNetwork(
        topology_file=args.topology or 'topology.json',
        random_seed=args.seed
    )
    network.load_default_topology()
    network.initialize_routing_tables()
    
    # Create visualizer
    visualizer = NetworkVisualizer(network, width=args.width, height=args.height)
    
    # Start simulation thread
    sim_thread = threading.Thread(target=run_simulation_thread, args=(network,), daemon=True)
    sim_thread.start()
    
    try:
        # Run visualization in main thread (required for macOS)
        visualizer.run()
    except KeyboardInterrupt:
        print("\nShutting down...")
    except Exception as e:
        print(f"Visualization error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        print("Stopping simulation...")
        network.stop_simulation()
        
        sim_thread.join(timeout=2)
        if sim_thread.is_alive():
            print("Warning: Simulation thread did not exit cleanly")
        
        print("Application closed.")
        print(f"\nCheck the '{os.path.abspath('rip_logs')}' directory for detailed routing table logs.")


if __name__ == "__main__":
    main()