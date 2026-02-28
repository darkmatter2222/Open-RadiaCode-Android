"""
Vega 2D Model - Simple 3-layer CNN

Ultra-efficient model: 3 conv layers + 1 dense layer.
Uses Global Average Pooling to minimize parameters.

Input shape: (batch, 1, time_intervals, channels) = (B, 1, 300, 1023)
"""

import torch
import torch.nn as nn
from dataclasses import dataclass
from typing import Tuple


@dataclass
class Vega2DConfig:
    """Configuration for Vega 2D model."""
    
    # Input dimensions
    num_channels: int = 1023  # Energy channels
    num_time_intervals: int = 300  # Time dimension
    
    # Output
    num_isotopes: int = 82
    
    # CNN architecture - micro 3 layers
    conv1_channels: int = 4
    conv2_channels: int = 8
    conv3_channels: int = 16
    kernel_size: Tuple[int, int] = (3, 3)  # (time, energy)
    pool_size: Tuple[int, int] = (5, 5)  # Very aggressive pooling
    
    # Single dense layer size
    fc_dim: int = 32
    
    # Regularization
    dropout_rate: float = 0.3
    
    # Activity scaling
    max_activity_bq: float = 1000.0


class Vega2DModel(nn.Module):
    """
    Simple 3-layer CNN for gamma spectrum isotope identification.
    
    Architecture:
        Conv2d(1->32) -> BN -> ReLU -> Pool
        Conv2d(32->64) -> BN -> ReLU -> Pool  
        Conv2d(64->128) -> BN -> ReLU -> Pool
        GlobalAvgPool -> Flatten -> Dense(256) -> Output heads
    """
    
    def __init__(self, config: Vega2DConfig = None):
        super().__init__()
        self.config = config or Vega2DConfig()
        
        # Padding to preserve spatial dims
        pad = (self.config.kernel_size[0] // 2, self.config.kernel_size[1] // 2)
        
        # Layer 1
        self.conv1 = nn.Conv2d(1, self.config.conv1_channels, self.config.kernel_size, padding=pad)
        self.bn1 = nn.BatchNorm2d(self.config.conv1_channels)
        
        # Layer 2
        self.conv2 = nn.Conv2d(self.config.conv1_channels, self.config.conv2_channels, self.config.kernel_size, padding=pad)
        self.bn2 = nn.BatchNorm2d(self.config.conv2_channels)
        
        # Layer 3
        self.conv3 = nn.Conv2d(self.config.conv2_channels, self.config.conv3_channels, self.config.kernel_size, padding=pad)
        self.bn3 = nn.BatchNorm2d(self.config.conv3_channels)
        
        # Pooling and activation
        self.pool = nn.MaxPool2d(self.config.pool_size)
        self.relu = nn.ReLU(inplace=True)
        self.dropout = nn.Dropout2d(self.config.dropout_rate)
        
        # Global Average Pooling
        self.global_pool = nn.AdaptiveAvgPool2d(1)
        
        # Single dense layer
        self.fc = nn.Linear(self.config.conv3_channels, self.config.fc_dim)
        self.fc_bn = nn.BatchNorm1d(self.config.fc_dim)
        self.fc_dropout = nn.Dropout(self.config.dropout_rate)
        
        # Output heads
        self.classifier = nn.Linear(self.config.fc_dim, self.config.num_isotopes)
        self.regressor = nn.Sequential(
            nn.Linear(self.config.fc_dim, self.config.num_isotopes),
            nn.ReLU()
        )
        
        self._init_weights()
    
    def _init_weights(self):
        """Kaiming initialization."""
        for m in self.modules():
            if isinstance(m, nn.Conv2d):
                nn.init.kaiming_normal_(m.weight, mode='fan_out', nonlinearity='relu')
            elif isinstance(m, nn.Linear):
                nn.init.kaiming_normal_(m.weight, mode='fan_out', nonlinearity='relu')
                if m.bias is not None:
                    nn.init.constant_(m.bias, 0)
            elif isinstance(m, (nn.BatchNorm1d, nn.BatchNorm2d)):
                nn.init.constant_(m.weight, 1)
                nn.init.constant_(m.bias, 0)
    
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """Forward pass."""
        # Add channel dim if needed: (B, T, C) -> (B, 1, T, C)
        if x.dim() == 3:
            x = x.unsqueeze(1)
        
        # Conv block 1
        x = self.pool(self.relu(self.bn1(self.conv1(x))))
        x = self.dropout(x)
        
        # Conv block 2
        x = self.pool(self.relu(self.bn2(self.conv2(x))))
        x = self.dropout(x)
        
        # Conv block 3
        x = self.pool(self.relu(self.bn3(self.conv3(x))))
        x = self.dropout(x)
        
        # GAP -> flatten
        x = self.global_pool(x)
        x = x.view(x.size(0), -1)
        
        # Dense layer
        x = self.fc_dropout(self.relu(self.fc_bn(self.fc(x))))
        
        # Output heads
        logits = self.classifier(x)
        activities = self.regressor(x)
        
        return logits, activities
    
    def predict(self, x: torch.Tensor, threshold: float = 0.5) -> Tuple[torch.Tensor, torch.Tensor]:
        """Predict isotope presence and activities."""
        logits, activities_norm = self.forward(x)
        probs = torch.sigmoid(logits)
        presence = (probs >= threshold).float()
        activities_bq = activities_norm * self.config.max_activity_bq
        return presence, activities_bq


def count_parameters(model: nn.Module) -> int:
    """Count trainable parameters."""
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


if __name__ == "__main__":
    # Test the model
    config = Vega2DConfig()
    model = Vega2DModel(config)
    
    print(f"Vega 2D Model")
    print(f"  Input: ({config.num_time_intervals}, {config.num_channels})")
    print(f"  Conv channels: {config.conv_channels}")
    print(f"  FC dims: {config.fc_hidden_dims}")
    print(f"  Flat size: {model.flat_size}")
    print(f"  Parameters: {count_parameters(model):,}")
    
    # Test forward pass
    batch = torch.randn(4, 1, config.num_time_intervals, config.num_channels)
    logits, activities = model(batch)
    print(f"\n  Test batch: {batch.shape}")
    print(f"  Logits: {logits.shape}")
    print(f"  Activities: {activities.shape}")
