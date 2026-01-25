"""
Vega Model Architecture - CNN-FCNN with Multi-Task Heads

A hybrid Convolutional Neural Network with Fully Connected Neural Network
for gamma spectrum isotope identification. Based on peer-reviewed research
showing CNN-FCNN achieves state-of-the-art performance (99%+ accuracy).

Architecture:
    Input: 1D gamma spectrum (1023 channels, 20-3000 keV)
    ↓
    Feature Extraction: 3 CNN modules with LeakyReLU, MaxPool, Dropout
    ↓
    Classification Head: Dense layers → Sigmoid (multi-label isotope presence)
    ↓
    Regression Head: Dense layers → ReLU (activity estimation in Bq)
"""

import torch
import torch.nn as nn
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


@dataclass
class VegaConfig:
    """Configuration for the Vega model."""
    
    # Input configuration
    num_channels: int = 1023  # Number of energy channels in spectrum
    
    # Number of isotopes to classify
    num_isotopes: int = 82  # From isotope database
    
    # CNN backbone configuration
    conv_channels: List[int] = field(default_factory=lambda: [64, 128, 256])
    conv_kernel_size: int = 7
    pool_size: int = 2
    
    # Classification head configuration
    fc_hidden_dims: List[int] = field(default_factory=lambda: [512, 256])
    
    # Regularization
    dropout_rate: float = 0.3
    spatial_dropout_rate: float = 0.1
    
    # Activation
    leaky_relu_slope: float = 0.1
    
    # Loss weighting
    classification_weight: float = 1.0
    regression_weight: float = 0.1
    
    # Training
    max_activity_bq: float = 1000.0  # For activity normalization
    

class ConvBlock(nn.Module):
    """
    Convolutional block with two conv layers, activation, pooling, and dropout.
    
    Based on Turner et al. (2021) architecture showing that stacking two
    convolutions per module with pooling achieves good feature extraction.
    """
    
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int = 7,
        pool_size: int = 2,
        dropout_rate: float = 0.1,
        leaky_slope: float = 0.1
    ):
        super().__init__()
        
        # First convolution
        self.conv1 = nn.Conv1d(
            in_channels, out_channels, 
            kernel_size=kernel_size, 
            padding=kernel_size // 2
        )
        self.bn1 = nn.BatchNorm1d(out_channels)
        self.act1 = nn.LeakyReLU(leaky_slope)
        
        # Second convolution
        self.conv2 = nn.Conv1d(
            out_channels, out_channels,
            kernel_size=kernel_size,
            padding=kernel_size // 2
        )
        self.bn2 = nn.BatchNorm1d(out_channels)
        self.act2 = nn.LeakyReLU(leaky_slope)
        
        # Pooling and dropout
        self.pool = nn.MaxPool1d(pool_size)
        self.dropout = nn.Dropout1d(dropout_rate)  # Spatial dropout for 1D
        
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # First conv block
        x = self.conv1(x)
        x = self.bn1(x)
        x = self.act1(x)
        
        # Second conv block
        x = self.conv2(x)
        x = self.bn2(x)
        x = self.act2(x)
        
        # Pool and dropout
        x = self.pool(x)
        x = self.dropout(x)
        
        return x


class VegaModel(nn.Module):
    """
    Vega: CNN-FCNN Multi-Task Model for Isotope Identification
    
    Named after the bright star Vega (α Lyrae), which emits radiation
    across the electromagnetic spectrum - fitting for a gamma spectrum analyzer.
    
    The model performs two tasks:
    1. Multi-label classification: Which isotopes are present?
    2. Activity regression: What is the activity (Bq) of each isotope?
    """
    
    def __init__(self, config: VegaConfig):
        super().__init__()
        self.config = config
        
        # Build CNN backbone
        self.backbone = self._build_backbone()
        
        # Calculate flattened size after backbone
        self._flat_size = self._calculate_flat_size()
        
        # Build classification head (multi-label)
        self.classifier = self._build_classifier()
        
        # Build regression head (activity estimation)
        self.regressor = self._build_regressor()
        
        # Initialize weights
        self._init_weights()
    
    def _build_backbone(self) -> nn.Sequential:
        """Build the CNN feature extraction backbone."""
        layers = []
        in_channels = 1  # Input is 1D spectrum with 1 channel
        
        for out_channels in self.config.conv_channels:
            layers.append(ConvBlock(
                in_channels=in_channels,
                out_channels=out_channels,
                kernel_size=self.config.conv_kernel_size,
                pool_size=self.config.pool_size,
                dropout_rate=self.config.spatial_dropout_rate,
                leaky_slope=self.config.leaky_relu_slope
            ))
            in_channels = out_channels
        
        return nn.Sequential(*layers)
    
    def _calculate_flat_size(self) -> int:
        """Calculate the size of flattened features after backbone."""
        # Create dummy input to calculate size
        dummy = torch.zeros(1, 1, self.config.num_channels)
        with torch.no_grad():
            out = self.backbone(dummy)
        return out.numel()
    
    def _build_classifier(self) -> nn.Sequential:
        """Build the classification head for isotope presence prediction.
        
        Outputs raw logits (not probabilities) for AMP compatibility.
        Use BCEWithLogitsLoss for training, apply sigmoid during inference.
        """
        layers = []
        in_features = self._flat_size
        
        # Hidden layers
        for hidden_dim in self.config.fc_hidden_dims:
            layers.extend([
                nn.Linear(in_features, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            in_features = hidden_dim
        
        # Output layer - raw logits for AMP compatibility
        layers.append(nn.Linear(in_features, self.config.num_isotopes))
        
        return nn.Sequential(*layers)
    
    def _build_regressor(self) -> nn.Sequential:
        """Build the regression head for activity estimation."""
        layers = []
        in_features = self._flat_size
        
        # Hidden layers (shared architecture with classifier)
        for hidden_dim in self.config.fc_hidden_dims:
            layers.extend([
                nn.Linear(in_features, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            in_features = hidden_dim
        
        # Output layer with ReLU for non-negative activity values
        layers.extend([
            nn.Linear(in_features, self.config.num_isotopes),
            nn.ReLU()  # Activity must be non-negative
        ])
        
        return nn.Sequential(*layers)
    
    def _init_weights(self):
        """Initialize weights using He initialization for LeakyReLU."""
        for module in self.modules():
            if isinstance(module, (nn.Conv1d, nn.Linear)):
                nn.init.kaiming_normal_(
                    module.weight, 
                    a=self.config.leaky_relu_slope,
                    mode='fan_out',
                    nonlinearity='leaky_relu'
                )
                if module.bias is not None:
                    nn.init.zeros_(module.bias)
            elif isinstance(module, nn.BatchNorm1d):
                nn.init.ones_(module.weight)
                nn.init.zeros_(module.bias)
    
    def forward(
        self, 
        x: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Forward pass through the model.
        
        Args:
            x: Input spectrum tensor of shape (batch, channels) or (batch, 1, channels)
               Values should be normalized [0, 1]
        
        Returns:
            Tuple of:
                - isotope_logits: Raw logits for each isotope (batch, num_isotopes)
                  Apply sigmoid to get probabilities for inference
                - activity_pred: Predicted activity in Bq for each isotope (batch, num_isotopes)
        """
        # Ensure input has channel dimension
        if x.dim() == 2:
            x = x.unsqueeze(1)  # (batch, channels) -> (batch, 1, channels)
        
        # Feature extraction
        features = self.backbone(x)
        features = features.flatten(start_dim=1)
        
        # Classification head (outputs logits)
        isotope_logits = self.classifier(features)
        
        # Regression head
        activity_pred = self.regressor(features)
        
        return isotope_logits, activity_pred
    
    def predict(
        self,
        x: torch.Tensor,
        threshold: float = 0.5,
        return_all: bool = False
    ) -> Dict:
        """
        Make predictions with post-processing.
        
        Args:
            x: Input spectrum tensor
            threshold: Probability threshold for isotope presence
            return_all: If True, return predictions for all isotopes
            
        Returns:
            Dictionary with predictions
        """
        self.eval()
        with torch.no_grad():
            probs, activities = self(x)
        
        # Apply threshold
        present = probs >= threshold
        
        # Mask activities by presence
        masked_activities = activities * present.float()
        
        return {
            'probabilities': probs,
            'activities_bq': masked_activities * self.config.max_activity_bq,
            'present_mask': present,
            'threshold': threshold
        }
    
    def count_parameters(self) -> int:
        """Count total trainable parameters."""
        return sum(p.numel() for p in self.parameters() if p.requires_grad)
    
    def summary(self) -> str:
        """Get a summary of the model architecture."""
        lines = [
            "=" * 60,
            "VEGA Model - CNN-FCNN Multi-Task Isotope Identifier",
            "=" * 60,
            f"Input channels: {self.config.num_channels}",
            f"Output isotopes: {self.config.num_isotopes}",
            f"CNN channels: {self.config.conv_channels}",
            f"FC hidden dims: {self.config.fc_hidden_dims}",
            f"Dropout rate: {self.config.dropout_rate}",
            f"Total parameters: {self.count_parameters():,}",
            "=" * 60
        ]
        return "\n".join(lines)


class VegaLoss(nn.Module):
    """
    Combined loss function for Vega multi-task learning.
    
    Combines:
    - Binary Cross-Entropy for isotope classification (multi-label)
    - Huber Loss for activity regression (robust to outliers)
    """
    
    def __init__(
        self,
        classification_weight: float = 1.0,
        regression_weight: float = 0.1,
        huber_delta: float = 1.0
    ):
        super().__init__()
        self.classification_weight = classification_weight
        self.regression_weight = regression_weight
        
        # Use BCEWithLogitsLoss for AMP safety (combines sigmoid + BCE)
        self.bce_loss = nn.BCEWithLogitsLoss()
        self.huber_loss = nn.HuberLoss(delta=huber_delta)
    
    def forward(
        self,
        pred_logits: torch.Tensor,
        pred_activities: torch.Tensor,
        target_presence: torch.Tensor,
        target_activities: torch.Tensor
    ) -> Tuple[torch.Tensor, Dict[str, float]]:
        """
        Calculate combined loss.
        
        Args:
            pred_logits: Predicted isotope logits (batch, num_isotopes)
            pred_activities: Predicted activities (batch, num_isotopes)
            target_presence: Ground truth presence labels (batch, num_isotopes)
            target_activities: Ground truth activities (batch, num_isotopes)
            
        Returns:
            Tuple of total loss and dict of individual losses
        """
        # Classification loss (BCEWithLogitsLoss applies sigmoid internally)
        cls_loss = self.bce_loss(pred_logits, target_presence.float())
        
        # Regression loss (only for present isotopes)
        # Mask to only compute loss where isotopes are actually present
        mask = target_presence.float()
        if mask.sum() > 0:
            masked_pred = pred_activities * mask
            masked_target = target_activities * mask
            reg_loss = self.huber_loss(masked_pred, masked_target)
        else:
            reg_loss = torch.tensor(0.0, device=pred_activities.device)
        
        # Combined loss
        total_loss = (
            self.classification_weight * cls_loss + 
            self.regression_weight * reg_loss
        )
        
        loss_dict = {
            'total': total_loss.item(),
            'classification': cls_loss.item(),
            'regression': reg_loss.item() if isinstance(reg_loss, torch.Tensor) else reg_loss
        }
        
        return total_loss, loss_dict


if __name__ == "__main__":
    # Test the model
    config = VegaConfig()
    model = VegaModel(config)
    
    print(model.summary())
    
    # Test forward pass
    batch_size = 4
    x = torch.randn(batch_size, config.num_channels)
    
    probs, activities = model(x)
    print(f"\nInput shape: {x.shape}")
    print(f"Output probs shape: {probs.shape}")
    print(f"Output activities shape: {activities.shape}")
    
    # Test loss
    loss_fn = VegaLoss()
    target_presence = torch.randint(0, 2, (batch_size, config.num_isotopes))
    target_activities = torch.rand(batch_size, config.num_isotopes) * 100
    
    loss, loss_dict = loss_fn(probs, activities, target_presence, target_activities)
    print(f"\nLoss: {loss_dict}")
